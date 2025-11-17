package org.thingsboard.rule.engine.anomaly;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

import java.util.List;

@Data
public class TbIForestAnomalyNodeConfiguration implements NodeConfiguration<TbIForestAnomalyNodeConfiguration> {

    private String modelId;
    private List<String> features;
    private String scoreField;
    private double threshold;
    private int smoothWindow;
    private Direction direction;
    private String outputKey;
    private boolean persistState;

    @Override
    public TbIForestAnomalyNodeConfiguration defaultConfiguration() {
        TbIForestAnomalyNodeConfiguration configuration = new TbIForestAnomalyNodeConfiguration();
        configuration.setModelId("iforest-batt-v1");
        configuration.setFeatures(List.of(
                "metrics.temperature",
                "metrics.terminal_voltage",
                "metrics.terminal_current",
                "metrics.SOH"
        ));
        configuration.setScoreField("score");
        configuration.setThreshold(0.9);
        configuration.setSmoothWindow(3);
        configuration.setDirection(Direction.HIGHER_IS_MORE_ANOMALOUS);
        configuration.setOutputKey("iforest");
        configuration.setPersistState(true);
        return configuration;
    }

    public enum Direction {
        HIGHER_IS_MORE_ANOMALOUS,
        LOWER_IS_MORE_ANOMALOUS
    }
}
