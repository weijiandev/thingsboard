package org.thingsboard.rule.engine.anomaly;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.rule.RuleNodeState;
import org.thingsboard.server.common.msg.TbMsg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RuleNode(
        type = ComponentType.FILTER,
        name = "ewma detector",
        relationTypes = {TbNodeConnectionType.TRUE, TbNodeConnectionType.FALSE},
        configClazz = TbEwmaAnomalyNodeConfiguration.class,
        nodeDescription = "Detect anomalies in the incoming metric stream using an EWMA control chart.",
        nodeDetails = "The node keeps per-originator running statistics and emits detector results " +
                "into the message body under <code>detectors.&lt;outputKey&gt;</code>.",
        uiResources = {"static/rulenode/rulenode-core-config.js"}
)
public class TbEwmaAnomalyNode implements TbNode {

    private TbEwmaAnomalyNodeConfiguration config;
    private final Map<String, EwmaState> states = new ConcurrentHashMap<>();

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbEwmaAnomalyNodeConfiguration.class);
        if (config.getField() == null || config.getField().isEmpty()) {
            throw new TbNodeException("Field must be specified for EWMA detector");
        }
        if (config.getAlpha() <= 0 || config.getAlpha() >= 1) {
            throw new TbNodeException("Alpha must be within (0,1) range");
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        try {
            ObjectNode data = (ObjectNode) JacksonUtil.toJsonNode(msg.getData());
            Double value = JsonPathHelper.getDouble(data, config.getField());
            if (value == null) {
                if (config.getMissingPolicy() == TbEwmaAnomalyNodeConfiguration.MissingPolicy.FAIL) {
                    throw new TbNodeException("Metric value is missing at path: " + config.getField());
                }
                ctx.tellNext(msg, TbNodeConnectionType.FALSE);
                return;
            }

            EwmaState state = states.computeIfAbsent(msg.getOriginator().getId().toString(),
                    id -> loadState(ctx, msg, id));
            state.update(value, config);

            double sigma = state.getStats().getStdDev();
            double ewma = state.getEwma();
            double z = sigma > 0 ? (value - ewma) / sigma : 0.0;
            boolean anomaly = state.isReady(config) && exceedsThreshold(value, ewma, sigma, config.getSigmaThreshold(), config.getDirection());

            ObjectNode detectorsNode = data.with("detectors");
            ObjectNode ewmaNode = detectorsNode.with(config.getOutputKey());
            ewmaNode.put("isAnomaly", anomaly);
            ewmaNode.put("z", z);
            ewmaNode.put("ewma", ewma);
            ewmaNode.put("alpha", config.getAlpha());
            ewmaNode.put("sigmaThreshold", config.getSigmaThreshold());

            persistState(ctx, msg, state);

            TbMsg newMsg = ctx.transformMsg(msg, msg.getMetaData(), JacksonUtil.toString(data));
            ctx.tellNext(newMsg, anomaly ? TbNodeConnectionType.TRUE : TbNodeConnectionType.FALSE);
        } catch (Exception e) {
            log.warn("[{}] Failed to process message", ctx.getSelfId(), e);
            ctx.tellFailure(msg, e);
        }
    }

    private boolean exceedsThreshold(double value, double ewma, double sigma, double threshold,
                                     TbEwmaAnomalyNodeConfiguration.Direction direction) {
        if (sigma == 0) {
            return false;
        }
        double deviation = value - ewma;
        double limit = threshold * sigma;
        return switch (direction) {
            case UP -> deviation > limit;
            case DOWN -> -deviation > limit;
            case BOTH -> Math.abs(deviation) > limit;
        };
    }

    private EwmaState loadState(TbContext ctx, TbMsg msg, String key) {
        RuleNodeState persisted = ctx.findRuleNodeStateForEntity(msg.getOriginator());
        if (persisted != null && persisted.getStateData() != null) {
            EwmaPersistedState persistedState = JacksonUtil.fromString(persisted.getStateData(), EwmaPersistedState.class);
            EwmaState state = new EwmaState();
            state.setPersisted(persisted);
            if (persistedState != null) {
                state.setStats(persistedState.getStats() != null ? persistedState.getStats() : new RunningStatistics());
                state.setEwma(persistedState.getEwma());
                state.setInitialized(persistedState.isInitialized());
            }
            return state;
        } else {
            RuleNodeState state = new RuleNodeState();
            state.setRuleNodeId(ctx.getSelfId());
            state.setEntityId(msg.getOriginator());
            EwmaState ewmaState = new EwmaState();
            ewmaState.setPersisted(state);
            return ewmaState;
        }
    }

    private void persistState(TbContext ctx, TbMsg msg, EwmaState state) {
        if (!config.isPersistState()) {
            return;
        }
        try {
            EwmaPersistedState persisted = new EwmaPersistedState();
            persisted.setStats(state.getStats());
            persisted.setEwma(state.getEwma());
            persisted.setInitialized(state.isInitialized());
            state.getPersisted().setStateData(JacksonUtil.toString(persisted));
            state.setPersisted(ctx.saveRuleNodeState(state.getPersisted()));
        } catch (Exception e) {
            log.warn("[{}] Failed to persist EWMA state", ctx.getSelfId(), e);
        }
    }

    @Data
    private static class EwmaState {
        private RunningStatistics stats = new RunningStatistics();
        private double ewma;
        private boolean initialized;
        private RuleNodeState persisted;

        void update(double value, TbEwmaAnomalyNodeConfiguration config) {
            stats.addSample(value);
            if (!initialized && stats.getCount() >= config.getInitPoints()) {
                ewma = stats.getMean();
                initialized = true;
            } else if (initialized) {
                ewma = config.getAlpha() * value + (1 - config.getAlpha()) * ewma;
            }
        }

        boolean isReady(TbEwmaAnomalyNodeConfiguration config) {
            return initialized && stats.getCount() >= config.getMinHistory();
        }
    }

    @Data
    private static class EwmaPersistedState {
        private RunningStatistics stats;
        private double ewma;
        private boolean initialized;
    }

}
