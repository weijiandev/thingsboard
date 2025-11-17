package org.thingsboard.rule.engine.anomaly;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.data.rule.RuleNodeState;
import org.thingsboard.server.common.msg.TbMsg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RuleNode(
        type = ComponentType.FILTER,
        name = "fusion strategy",
        relationTypes = {TbNodeConnectionType.TRUE, TbNodeConnectionType.FALSE},
        configClazz = TemperatureAlarmStrategyConfig.class,
        nodeDescription = "Fuse results of detector nodes and derive a final alarm severity.",
        nodeDetails = "The node consumes detector outputs from the message payload and applies configurable " +
                "rules to derive a final severity. The matching rule details are placed under the <code>fusion</code> key.",
        uiResources = {"static/rulenode/rulenode-core-config.js"}
)
public class TbFusionAnomalyNode implements TbNode {

    private TemperatureAlarmStrategyConfig config;
    private final Map<String, FusionState> states = new ConcurrentHashMap<>();

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        this.config = TbNodeUtils.convert(configuration, TemperatureAlarmStrategyConfig.class);
        if (config.getFusion() == null || config.getFusion().getRules() == null || config.getFusion().getRules().isEmpty()) {
            throw new TbNodeException("At least one fusion rule must be configured");
        }
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) {
        try {
            ObjectNode data = (ObjectNode) JacksonUtil.toJsonNode(msg.getData());
            JsonNode detectorsNode = data.get("detectors");
            if (!(detectorsNode instanceof ObjectNode detectorsObject)) {
                ctx.tellNext(msg, TbNodeConnectionType.FALSE);
                return;
            }

            FusionState state = states.computeIfAbsent(msg.getOriginator().getId().toString(),
                    id -> loadState(ctx, msg));

            Optional<FusionOutcome> outcomeOpt = evaluate(detectorsObject);
            Optional<FusionOutcome> allowedOutcome = outcomeOpt.filter(outcome -> allowTrigger(state, outcome.getSeverity()));
            boolean triggered = allowedOutcome.isPresent();

            ObjectNode fusionNode = data.with("fusion");
            fusionNode.put("strategyId", config.getStrategyId());
            fusionNode.put("alarmCode", config.getAlarmCode());

            if (triggered && allowedOutcome.isPresent()) {
                FusionOutcome outcome = allowedOutcome.get();
                fusionNode.put("rule", outcome.getRuleName());
                fusionNode.put("severity", outcome.getSeverity().name());
                fusionNode.put("score", outcome.getScore() != null ? outcome.getScore() : Double.NaN);

                state.updateSuccess(config.getFusion(), outcome.getSeverity());
                persistState(ctx, state);

                TbMsg transformed = augmentMsg(ctx, msg, data, outcome);
                ctx.tellNext(transformed, TbNodeConnectionType.TRUE);
            } else {
                fusionNode.put("rule", (String) null);
                fusionNode.put("severity", (String) null);
                fusionNode.put("score", Double.NaN);
                persistState(ctx, state);
                TbMsg transformed = ctx.transformMsg(msg, msg.getMetaData(), JacksonUtil.toString(data));
                ctx.tellNext(transformed, TbNodeConnectionType.FALSE);
            }
        } catch (Exception e) {
            log.warn("[{}] Failed to process fusion", ctx.getSelfId(), e);
            ctx.tellFailure(msg, e);
        }
    }

    private TbMsg augmentMsg(TbContext ctx, TbMsg msg, ObjectNode data, FusionOutcome outcome) {
        var metadata = msg.getMetaData().copy();
        metadata.putValue("alarmCode", config.getAlarmCode());
        metadata.putValue("alarmSeverity", outcome.getSeverity().name());
        if (config.getStrategyId() != null) {
            metadata.putValue("strategyId", config.getStrategyId());
        }
        metadata.putValue("fusionRule", outcome.getRuleName());
        return ctx.transformMsg(msg, metadata, JacksonUtil.toString(data));
    }

    private Optional<FusionOutcome> evaluate(ObjectNode detectorsNode) {
        List<FusionOutcome> matches = new ArrayList<>();
        for (TemperatureAlarmStrategyConfig.FusionRuleConfig rule : config.getFusion().getRules()) {
            FusionOutcome outcome = switch (rule.getType()) {
                case M_OF_N -> evaluateVoteRule(detectorsNode, rule);
                case WEIGHTED_SCORE -> evaluateWeightedRule(detectorsNode, rule);
            };
            if (outcome != null) {
                matches.add(outcome);
            }
        }

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (config.getFusion().getRulesLogic() == TemperatureAlarmStrategyConfig.RulesLogic.ALL
                && matches.size() != config.getFusion().getRules().size()) {
            return Optional.empty();
        }
        return matches.stream().max(Comparator.comparing(FusionOutcome::getSeverity, AlarmSeverity::compareTo));
    }

    private FusionOutcome evaluateVoteRule(ObjectNode detectorsNode, TemperatureAlarmStrategyConfig.FusionRuleConfig rule) {
        if (rule.getDetectors() == null || rule.getDetectors().isEmpty()) {
            return null;
        }
        int required = rule.getM() != null ? rule.getM() : rule.getDetectors().size();
        int votes = 0;
        for (String detector : rule.getDetectors()) {
            JsonNode detectorNode = detectorsNode.get(detector);
            if (detectorNode != null && detectorNode.has("isAnomaly") && detectorNode.get("isAnomaly").asBoolean()) {
                votes++;
            }
        }
        if (votes >= required && rule.getSeverity() != null) {
            return new FusionOutcome(rule.getName(), rule.getSeverity(), null);
        }
        return null;
    }

    private FusionOutcome evaluateWeightedRule(ObjectNode detectorsNode, TemperatureAlarmStrategyConfig.FusionRuleConfig rule) {
        if (rule.getDetectorWeights() == null || rule.getDetectorWeights().isEmpty()) {
            return null;
        }
        double totalWeight = 0;
        double weightedScore = 0;
        for (Map.Entry<String, Double> entry : rule.getDetectorWeights().entrySet()) {
            String detector = entry.getKey();
            double weight = entry.getValue() == null ? 0 : entry.getValue();
            if (weight <= 0) {
                continue;
            }
            JsonNode detectorNode = detectorsNode.get(detector);
            if (detectorNode == null) {
                continue;
            }
            TemperatureAlarmStrategyConfig.ScoreMapping mapping = rule.getScoreMappings() != null
                    ? rule.getScoreMappings().get(detector)
                    : null;
            String sourceField = mapping != null && mapping.getSourceField() != null
                    ? mapping.getSourceField() : "score";
            JsonNode scoreNode = detectorNode.get(sourceField);
            if (scoreNode == null || !scoreNode.isNumber()) {
                continue;
            }
            double score = scoreNode.asDouble();
            if (mapping != null && mapping.getTransform() != null) {
                score = applyTransform(mapping.getTransform(), score);
            }
            weightedScore += weight * score;
            totalWeight += weight;
        }
        if (totalWeight == 0) {
            return null;
        }
        double aggregated = weightedScore / totalWeight;
        if (rule.getScoreThreshold() != null && aggregated < rule.getScoreThreshold()) {
            return null;
        }
        AlarmSeverity severity = rule.getSeverity();
        if (rule.getSeverityByScore() != null) {
            severity = rule.getSeverityByScore().stream()
                    .filter(mapping -> aggregated >= mapping.getMin())
                    .sorted(Comparator.comparingDouble(TemperatureAlarmStrategyConfig.SeverityByScore::getMin).reversed())
                    .map(TemperatureAlarmStrategyConfig.SeverityByScore::getSeverity)
                    .findFirst()
                    .orElse(severity);
        }
        if (severity == null) {
            severity = AlarmSeverity.MAJOR;
        }
        return new FusionOutcome(rule.getName(), severity, aggregated);
    }

    private double applyTransform(TemperatureAlarmStrategyConfig.ScoreTransform transform, double score) {
        return switch (transform) {
            case IDENTITY -> score;
        };
    }

    private boolean allowTrigger(FusionState state, AlarmSeverity severity) {
        TemperatureAlarmStrategyConfig.FusionConfig fusionConfig = config.getFusion();
        long now = System.currentTimeMillis();
        if (fusionConfig.getCooldownMs() > 0 && now - state.getLastTriggerTs() < fusionConfig.getCooldownMs()) {
            return false;
        }
        if (fusionConfig.getDedupWindowMs() > 0 && now - state.getLastTriggerTs() < fusionConfig.getDedupWindowMs()) {
            if (state.getLastSeverity() == severity) {
                int maxRepeat = fusionConfig.getMaxRepeatCount() <= 0 ? Integer.MAX_VALUE : fusionConfig.getMaxRepeatCount();
                return state.getRepeatCount() < maxRepeat;
            }
        }
        return true;
    }

    private FusionState loadState(TbContext ctx, TbMsg msg) {
        RuleNodeState persisted = ctx.findRuleNodeStateForEntity(msg.getOriginator());
        FusionState state = new FusionState();
        if (persisted != null && persisted.getStateData() != null) {
            FusionPersistedState persistedState = JacksonUtil.fromString(persisted.getStateData(), FusionPersistedState.class);
            if (persistedState != null) {
                state.setLastTriggerTs(persistedState.getLastTriggerTs());
                state.setRepeatCount(persistedState.getRepeatCount());
                state.setLastSeverity(persistedState.getLastSeverity());
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

    private void persistState(TbContext ctx, FusionState state) {
        try {
            FusionPersistedState persisted = new FusionPersistedState();
            persisted.setLastTriggerTs(state.getLastTriggerTs());
            persisted.setRepeatCount(state.getRepeatCount());
            persisted.setLastSeverity(state.getLastSeverity());
            state.getPersisted().setStateData(JacksonUtil.toString(persisted));
            state.setPersisted(ctx.saveRuleNodeState(state.getPersisted()));
        } catch (Exception e) {
            log.warn("[{}] Failed to persist fusion state", ctx.getSelfId(), e);
        }
    }

    @Data
    private static class FusionOutcome {
        private final String ruleName;
        private final AlarmSeverity severity;
        private final Double score;
    }

    @Data
    private static class FusionState {
        private long lastTriggerTs;
        private int repeatCount;
        private AlarmSeverity lastSeverity;
        private RuleNodeState persisted;

        void updateSuccess(TemperatureAlarmStrategyConfig.FusionConfig config, AlarmSeverity severity) {
            long now = System.currentTimeMillis();
            if (lastSeverity == severity && config.getDedupWindowMs() > 0
                    && now - lastTriggerTs < config.getDedupWindowMs()) {
                repeatCount++;
            } else {
                repeatCount = 1;
            }
            lastTriggerTs = now;
            lastSeverity = severity;
        }
    }

    @Data
    private static class FusionPersistedState {
        private long lastTriggerTs;
        private int repeatCount;
        private AlarmSeverity lastSeverity;
    }
}
