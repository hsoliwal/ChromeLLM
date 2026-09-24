package io.synexia.chromellm.video;

public record SceneCut(long frameIndex, double score) {
    public SceneCut {
        if (frameIndex < 0L) throw new IllegalArgumentException("frameIndex must be non-negative");
        if (!Double.isFinite(score) || score < 0d) throw new IllegalArgumentException("score must be finite and non-negative");
    }
}
