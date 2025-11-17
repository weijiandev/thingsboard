package org.thingsboard.rule.engine.anomaly;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

@Data
public class TbEwmaAnomalyNodeConfiguration implements NodeConfiguration<TbEwmaAnomalyNodeConfiguration> {

    private String field;
    private double alpha;
    private double sigmaThreshold;
    private int initPoints;
    private int minHistory;
    private MissingPolicy missingPolicy;
    private Direction direction;
    private String outputKey;
    private boolean persistState;

    @Override
    public TbEwmaAnomalyNodeConfiguration defaultConfiguration() {
        TbEwmaAnomalyNodeConfiguration configuration = new TbEwmaAnomalyNodeConfiguration();
        configuration.setField("metrics.temperature");
        configuration.setAlpha(0.2);
        configuration.setSigmaThreshold(3.0);
        configuration.setInitPoints(30);
        configuration.setMinHistory(30);
        configuration.setMissingPolicy(MissingPolicy.SKIP);
        configuration.setDirection(Direction.UP);
        configuration.setOutputKey("ewma");
        configuration.setPersistState(true);
        return configuration;
    }

    public enum MissingPolicy {
        SKIP,
        FAIL
    }

    public enum Direction {
        UP,
        DOWN,
        BOTH
    }
}
