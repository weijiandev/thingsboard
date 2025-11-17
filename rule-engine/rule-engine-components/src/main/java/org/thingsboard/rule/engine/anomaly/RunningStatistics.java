package org.thingsboard.rule.engine.anomaly;

import lombok.Data;

/**
 * Helper that implements Welford's online algorithm to maintain running statistics
 * for streaming detectors.
 */
@Data
public class RunningStatistics {

    private long count;
    private double mean;
    private double m2;

    public void addSample(double value) {
        count++;
        double delta = value - mean;
        mean += delta / count;
        double delta2 = value - mean;
        m2 += delta * delta2;
    }

    public double getVariance() {
        return count > 1 ? m2 / (count - 1) : 0.0;
    }

    public double getStdDev() {
        double variance = getVariance();
        return variance > 0 ? Math.sqrt(variance) : 0.0;
    }

}
