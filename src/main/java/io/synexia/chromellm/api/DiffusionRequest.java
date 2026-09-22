package io.synexia.chromellm.api;

import java.util.Objects;

public record DiffusionRequest(int width, int height, int channels, int steps, long seed, float guidanceScale, float eta, DiffusionCondition condition) {
    public DiffusionRequest {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be positive");
        if (channels != 1 && channels != 3 && channels != 4) throw new IllegalArgumentException("channels must be 1, 3, or 4");
        if (steps < 1) throw new IllegalArgumentException("steps must be positive");
        if (!Float.isFinite(guidanceScale) || guidanceScale < 0f) throw new IllegalArgumentException("guidanceScale must be finite and non-negative");
        if (!Float.isFinite(eta) || eta < 0f) throw new IllegalArgumentException("eta must be finite and non-negative");
        condition = Objects.requireNonNullElseGet(condition, DiffusionCondition::none);
    }
    public static DiffusionRequest standard(int width, int height, long seed) {
        return new DiffusionRequest(width, height, 3, 40, seed, 7.5f, 0f, DiffusionCondition.none());
    }
}
