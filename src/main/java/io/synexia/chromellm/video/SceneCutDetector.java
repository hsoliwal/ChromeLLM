package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

public final class SceneCutDetector {
    private final double threshold;
    private final int sampleStride;

    public SceneCutDetector(double threshold, int sampleStride) {
        if (!Double.isFinite(threshold) || threshold < 0d || threshold > 1d) {
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        }
        if (sampleStride < 1) throw new IllegalArgumentException("sampleStride must be positive");
        this.threshold = threshold;
        this.sampleStride = sampleStride;
    }

    public static SceneCutDetector standard() {
        return new SceneCutDetector(0.35d, 4);
    }

    public boolean isCut(RgbaFrame previous, RgbaFrame current) {
        return score(previous, current) >= threshold;
    }

    public double score(RgbaFrame previous, RgbaFrame current) {
        double pixel = FrameStatistics.meanAbsoluteLumaDifference(previous, current, sampleStride);
        double histogram = FrameStatistics.histogramDistance(previous, current, sampleStride);
        return pixel * (0.75d + 0.25d * histogram);
    }

    public double threshold() {
        return threshold;
    }

    public int sampleStride() {
        return sampleStride;
    }
}
