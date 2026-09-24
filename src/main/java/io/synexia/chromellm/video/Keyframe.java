package io.synexia.chromellm.video;

import java.util.Objects;

public record Keyframe(long frameIndex, float value, Easing easing) {
    public Keyframe {
        if (frameIndex < 0L) throw new IllegalArgumentException("frameIndex must be non-negative");
        if (!Float.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        easing = Objects.requireNonNullElse(easing, Easing.LINEAR);
    }
}
