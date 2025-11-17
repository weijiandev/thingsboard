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
        name = "cusum detector",
        relationTypes = {TbNodeConnectionType.TRUE, TbNodeConnectionType.FALSE},
        configClazz = TbCusumAnomalyNodeConfiguration.class,
        nodeDescription = "Detect anomalies using the CUSUM control chart algorithm.",
        nodeDetails = "The node calculates cumulative sums for positive and negative drifts and writes results " +
                "under <code>detectors.&lt;outputKey&gt;</code> in the message payload.",
        uiResources = {"static/rulenode/rulenode-core-config.js"}
)
public class TbCusumAnomalyNode implements TbNode {

    private TbCusumAnomalyNodeConfiguration config;
    private final Map<String, CusumState> states = new ConcurrentHashMap<>();

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbCusumAnomalyNodeConfiguration.class);
        if (config.getField() == null || config.getField().isEmpty()) {
            throw new TbNodeException("Field must be specified for CUSUM detector");
        }
        if (config.getH() <= 0) {
            throw new TbNodeException("CUSUM threshold h must be positive");
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        try {
            ObjectNode data = (ObjectNode) JacksonUtil.toJsonNode(msg.getData());
            Double value = JsonPathHelper.getDouble(data, config.getField());
            if (value == null) {
                ctx.tellNext(msg, TbNodeConnectionType.FALSE);
                return;
            }

            CusumState state = states.computeIfAbsent(msg.getOriginator().getId().toString(),
                    id -> loadState(ctx, msg));
            state.update(value, config);

            double score = Math.max(state.getCplus(), state.getCminus());
            boolean anomaly = state.isReady(config) && exceeds(state, config);

            ObjectNode detectorsNode = data.with("detectors");
            ObjectNode cusumNode = detectorsNode.with(config.getOutputKey());
            cusumNode.put("isAnomaly", anomaly);
            cusumNode.put("score", score);
            cusumNode.put("Cplus", state.getCplus());
            cusumNode.put("Cminus", state.getCminus());
            cusumNode.put("k", state.getK());
            cusumNode.put("h", config.getH());

            if (anomaly && config.getResetPolicy() == TbCusumAnomalyNodeConfiguration.ResetPolicy.ON_ALARM) {
                state.resetCusum();
            }

            persistState(ctx, state);

            TbMsg newMsg = ctx.transformMsg(msg, msg.getMetaData(), JacksonUtil.toString(data));
            ctx.tellNext(newMsg, anomaly ? TbNodeConnectionType.TRUE : TbNodeConnectionType.FALSE);
        } catch (Exception e) {
            log.warn("[{}] Failed to process message", ctx.getSelfId(), e);
            ctx.tellFailure(msg, e);
        }
    }

    private boolean exceeds(CusumState state, TbCusumAnomalyNodeConfiguration cfg) {
        return switch (cfg.getDirection()) {
            case UP -> state.getCplus() > cfg.getH();
            case DOWN -> state.getCminus() > cfg.getH();
            case BOTH -> state.getCplus() > cfg.getH() || state.getCminus() > cfg.getH();
        };
    }

    private CusumState loadState(TbContext ctx, TbMsg msg) {
        RuleNodeState persisted = ctx.findRuleNodeStateForEntity(msg.getOriginator());
        CusumState state = new CusumState();
        if (persisted != null && persisted.getStateData() != null) {
            CusumPersistedState persistedState = JacksonUtil.fromString(persisted.getStateData(), CusumPersistedState.class);
            if (persistedState != null) {
                state.setStats(persistedState.getStats() != null ? persistedState.getStats() : new RunningStatistics());
                state.setTargetMean(persistedState.getTargetMean());
                state.setSigma(persistedState.getSigma());
                state.setK(persistedState.getK());
                state.setCplus(persistedState.getCplus());
                state.setCminus(persistedState.getCminus());
                state.setReady(persistedState.isReady());
            }
            state.setPersisted(persisted);
        } else {
            RuleNodeState newState = new RuleNodeState();
            newState.setRuleNodeId(ctx.getSelfId());
            newState.setEntityId(msg.getOriginator());
            state.setPersisted(newState);
        }
        return state;
    }

    private void persistState(TbContext ctx, CusumState state) {
        if (!config.isPersistState()) {
            return;
        }
        try {
            CusumPersistedState persistedState = new CusumPersistedState();
            persistedState.setStats(state.getStats());
            persistedState.setTargetMean(state.getTargetMean());
            persistedState.setSigma(state.getSigma());
            persistedState.setK(state.getK());
            persistedState.setCplus(state.getCplus());
            persistedState.setCminus(state.getCminus());
            persistedState.setReady(state.isReady());
            state.getPersisted().setStateData(JacksonUtil.toString(persistedState));
            state.setPersisted(ctx.saveRuleNodeState(state.getPersisted()));
        } catch (Exception e) {
            log.warn("[{}] Failed to persist CUSUM state", ctx.getSelfId(), e);
        }
    }

    @Data
    private static class CusumState {
        private RunningStatistics stats = new RunningStatistics();
        private double cplus;
        private double cminus;
        private Double targetMean;
        private Double sigma;
        private double k;
        private boolean ready;
        private RuleNodeState persisted;

        void update(double value, TbCusumAnomalyNodeConfiguration cfg) {
            stats.addSample(value);
            if (!ready && stats.getCount() >= cfg.getMinHistory()) {
                initialise(cfg);
            }
            if (!ready) {
                return;
            }
            double diff = value - targetMean;
            cplus = Math.max(0, cplus + diff - k);
            cminus = Math.max(0, cminus - diff - k);
        }

        private void initialise(TbCusumAnomalyNodeConfiguration cfg) {
            if (cfg.getTargetMeanMode() == TbCusumAnomalyNodeConfiguration.TargetMeanMode.FIXED) {
                targetMean = cfg.getTargetMean();
            } else {
                targetMean = stats.getMean();
            }
            sigma = stats.getStdDev();
            if (sigma == null || sigma == 0) {
                sigma = 1e-6;
            }
            if (cfg.getKMode() == TbCusumAnomalyNodeConfiguration.KMode.AUTO_SIGMA) {
                k = cfg.getKSigmaFactor() * sigma;
            } else {
                k = cfg.getFixedK();
            }
            ready = true;
        }

        void resetCusum() {
            cplus = 0;
            cminus = 0;
        }

        boolean isReady(TbCusumAnomalyNodeConfiguration cfg) {
            return ready && stats.getCount() >= cfg.getMinHistory();
        }
    }

    @Data
    private static class CusumPersistedState {
        private RunningStatistics stats;
        private Double targetMean;
        private Double sigma;
        private double k;
        private double cplus;
        private double cminus;
        private boolean ready;
    }
}
