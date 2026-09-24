package io.synexia.chromellm.video;

public record FrameTranslation(int dx, int dy, double score) {
    public FrameTranslation {
        if (!Double.isFinite(score) || score < 0d) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
    }
}
