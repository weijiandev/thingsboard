package org.thingsboard.rule.engine.anomaly;

import lombok.Data;
import org.thingsboard.rule.engine.api.NodeConfiguration;

@Data
public class TbCusumAnomalyNodeConfiguration implements NodeConfiguration<TbCusumAnomalyNodeConfiguration> {

    private String field;
    private int minHistory;
    private TargetMeanMode targetMeanMode;
    private Double targetMean;
    private KMode kMode;
    private double kSigmaFactor;
    private double fixedK;
    private double h;
    private Direction direction;
    private ResetPolicy resetPolicy;
    private String outputKey;
    private boolean persistState;

    @Override
    public TbCusumAnomalyNodeConfiguration defaultConfiguration() {
        TbCusumAnomalyNodeConfiguration configuration = new TbCusumAnomalyNodeConfiguration();
        configuration.setField("metrics.temperature");
        configuration.setMinHistory(30);
        configuration.setTargetMeanMode(TargetMeanMode.AUTO);
        configuration.setKMode(KMode.AUTO_SIGMA);
        configuration.setKSigmaFactor(0.5);
        configuration.setFixedK(0.0);
        configuration.setH(5.0);
        configuration.setDirection(Direction.UP);
        configuration.setResetPolicy(ResetPolicy.ON_ALARM);
        configuration.setOutputKey("cusum");
        configuration.setPersistState(true);
        return configuration;
    }

    public enum TargetMeanMode {
        AUTO,
        FIXED
    }

    public enum KMode {
        AUTO_SIGMA,
        FIXED
    }

    public enum Direction {
        UP,
        DOWN,
        BOTH
    }

    public enum ResetPolicy {
        NEVER,
        ON_ALARM
    }
}
