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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RuleNode(
        type = ComponentType.FILTER,
        name = "iforest detector",
        relationTypes = {TbNodeConnectionType.TRUE, TbNodeConnectionType.FALSE},
        configClazz = TbIForestAnomalyNodeConfiguration.class,
        nodeDescription = "Approximate an Isolation Forest anomaly score using streaming statistics.",
        nodeDetails = "The node approximates anomaly probabilities using z-score heuristics and " +
                "publishes the score under <code>detectors.&lt;outputKey&gt;</code>.",
        uiResources = {"static/rulenode/rulenode-core-config.js"}
)
public class TbIForestAnomalyNode implements TbNode {

    private TbIForestAnomalyNodeConfiguration config;
    private final Map<String, IForestState> states = new ConcurrentHashMap<>();

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TbIForestAnomalyNodeConfiguration.class);
        if (config.getFeatures() == null || config.getFeatures().isEmpty()) {
            throw new TbNodeException("At least one feature must be provided for isolation forest detector");
        }
        if (config.getSmoothWindow() <= 0) {
            throw new TbNodeException("Smooth window must be a positive integer");
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        try {
            ObjectNode data = (ObjectNode) JacksonUtil.toJsonNode(msg.getData());
            IForestState state = states.computeIfAbsent(msg.getOriginator().getId().toString(),
                    id -> loadState(ctx, msg));

            double maxZ = 0;
            boolean missingFeature = false;
            for (String feature : config.getFeatures()) {
                Double value = JsonPathHelper.getDouble(data, feature);
                if (value == null) {
                    missingFeature = true;
                    continue;
                }
                RunningStatistics stats = state.getStats().computeIfAbsent(feature, f -> new RunningStatistics());
                stats.addSample(value);
                double stdDev = stats.getStdDev();
                double z = stdDev > 0 ? (value - stats.getMean()) / stdDev : 0;
                if (config.getDirection() == TbIForestAnomalyNodeConfiguration.Direction.HIGHER_IS_MORE_ANOMALOUS) {
                    z = Math.max(0, z);
                } else {
                    z = Math.max(0, -z);
                }
                maxZ = Math.max(maxZ, Math.abs(z));
            }

            double score = toProbability(maxZ);
            boolean rawAnomaly = !missingFeature && score >= config.getThreshold();
            boolean smoothedAnomaly = state.push(rawAnomaly, config.getSmoothWindow());

            ObjectNode detectorsNode = data.with("detectors");
            ObjectNode iforestNode = detectorsNode.with(config.getOutputKey());
            iforestNode.put("isAnomaly", smoothedAnomaly);
            iforestNode.put("score", score);
            iforestNode.put("threshold", config.getThreshold());

            persistState(ctx, state);

            TbMsg newMsg = ctx.transformMsg(msg, msg.getMetaData(), JacksonUtil.toString(data));
            ctx.tellNext(newMsg, smoothedAnomaly ? TbNodeConnectionType.TRUE : TbNodeConnectionType.FALSE);
        } catch (Exception e) {
            log.warn("[{}] Failed to process message", ctx.getSelfId(), e);
            ctx.tellFailure(msg, e);
        }
    }

    private double toProbability(double maxZ) {
        if (Double.isNaN(maxZ) || Double.isInfinite(maxZ)) {
            return 1.0;
        }
        if (maxZ <= 0) {
            return 0.0;
        }
        return 1 - Math.exp(-0.5 * maxZ * maxZ);
    }

    private IForestState loadState(TbContext ctx, TbMsg msg) {
        RuleNodeState persisted = ctx.findRuleNodeStateForEntity(msg.getOriginator());
        IForestState state = new IForestState();
        if (persisted != null && persisted.getStateData() != null) {
            IForestPersistedState persistedState = JacksonUtil.fromString(persisted.getStateData(), IForestPersistedState.class);
            if (persistedState != null) {
                state.setStats(persistedState.getStats() != null ? persistedState.getStats() : new HashMap<>());
                if (persistedState.getWindow() != null) {
                    state.setWindow(new ArrayDeque<>(persistedState.getWindow()));
                }
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

    private void persistState(TbContext ctx, IForestState state) {
        if (!config.isPersistState()) {
            return;
        }
        try {
            IForestPersistedState persistedState = new IForestPersistedState();
            persistedState.setStats(state.getStats());
            persistedState.setWindow(List.copyOf(state.getWindow()));
            state.getPersisted().setStateData(JacksonUtil.toString(persistedState));
            state.setPersisted(ctx.saveRuleNodeState(state.getPersisted()));
        } catch (Exception e) {
            log.warn("[{}] Failed to persist isolation forest state", ctx.getSelfId(), e);
        }
    }

    @Data
    private static class IForestState {
        private Map<String, RunningStatistics> stats = new HashMap<>();
        private Deque<Boolean> window = new ArrayDeque<>();
        private RuleNodeState persisted;

        boolean push(boolean anomaly, int smoothWindow) {
            window.addLast(anomaly);
            while (window.size() > smoothWindow) {
                window.removeFirst();
            }
            if (window.size() < smoothWindow) {
                return false;
            }
            for (boolean flag : window) {
                if (!flag) {
                    return false;
                }
            }
            return true;
        }
    }

    @Data
    private static class IForestPersistedState {
        private Map<String, RunningStatistics> stats;
        private List<Boolean> window;
    }
}
