package org.thingsboard.rule.engine.anomaly;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;

import java.util.List;
import java.util.Map;

@Data
public class TemperatureAlarmStrategyConfig implements NodeConfiguration<TemperatureAlarmStrategyConfig> {

    private String strategyId;
    private String metricField;
    private String alarmCode;
    private String description;
    private FusionConfig fusion;

    @Override
    public TemperatureAlarmStrategyConfig defaultConfiguration() {
        TemperatureAlarmStrategyConfig config = new TemperatureAlarmStrategyConfig();
        config.setStrategyId("battery_temp_v1");
        config.setMetricField("metrics.temperature");
        config.setAlarmCode("TEMP_ABNORMAL");
        config.setDescription("电芯温度综合告警策略（EWMA + CUSUM + IForest）");

        FusionRuleConfig voteRule = new FusionRuleConfig();
        voteRule.setName("三算子两票通过");
        voteRule.setType(FusionRuleType.M_OF_N);
        voteRule.setDetectors(List.of("ewma", "cusum", "iforest"));
        voteRule.setM(2);
        voteRule.setSeverity(AlarmSeverity.MAJOR);

        FusionRuleConfig iforestRule = new FusionRuleConfig();
        iforestRule.setName("孤立森林极端异常");
        iforestRule.setType(FusionRuleType.WEIGHTED_SCORE);
        iforestRule.setDetectorWeights(Map.of("iforest", 1.0));
        ScoreMapping mapping = new ScoreMapping();
        mapping.setSourceField("score");
        mapping.setTransform(ScoreTransform.IDENTITY);
        iforestRule.setScoreMappings(Map.of("iforest", mapping));
        iforestRule.setScoreThreshold(0.97);
        iforestRule.setSeverityByScore(List.of(new SeverityByScore(0.97, AlarmSeverity.CRITICAL)));

        FusionConfig fusion = new FusionConfig();
        fusion.setMode(FusionMode.COMPOSITE);
        fusion.setRulesLogic(RulesLogic.ANY);
        fusion.setRules(List.of(voteRule, iforestRule));
        fusion.setCooldownMs(600_000);
        fusion.setDedupWindowMs(300_000);
        fusion.setMaxRepeatCount(3);

        config.setFusion(fusion);
        return config;
    }

    @Data
    public static class FusionConfig {
        private FusionMode mode;
        private RulesLogic rulesLogic;
        private List<FusionRuleConfig> rules;
        private long cooldownMs;
        private long dedupWindowMs;
        private int maxRepeatCount;
    }

    public enum FusionMode {
        COMPOSITE
    }

    public enum RulesLogic {
        ANY,
        ALL
    }

    @Data
    public static class FusionRuleConfig {
        private String name;
        private FusionRuleType type;
        private List<String> detectors;
        private Integer m;
        private AlarmSeverity severity;
        private Map<String, Double> detectorWeights;
        private Map<String, ScoreMapping> scoreMappings;
        private Double scoreThreshold;
        private List<SeverityByScore> severityByScore;
    }

    public enum FusionRuleType {
        M_OF_N,
        WEIGHTED_SCORE
    }

    @Data
    public static class ScoreMapping {
        private String sourceField;
        private ScoreTransform transform;
    }

    public enum ScoreTransform {
        IDENTITY
    }

    @Data
    public static class SeverityByScore {
        private double min;
        private AlarmSeverity severity;

        public SeverityByScore() {
        }

        public SeverityByScore(double min, AlarmSeverity severity) {
            this.min = min;
            this.severity = severity;
        }
    }
}
