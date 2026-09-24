package io.synexia.chromellm.video;

import java.util.Comparator;
import java.util.List;

public final class KeyframeCurve {
    private final List<Keyframe> keyframes;

    public KeyframeCurve(List<Keyframe> keyframes) {
        if (keyframes == null || keyframes.isEmpty()) {
            throw new IllegalArgumentException("at least one keyframe is required");
        }
        this.keyframes = keyframes.stream()
                .sorted(Comparator.comparingLong(Keyframe::frameIndex))
                .toList();
        for (int i = 1; i < this.keyframes.size(); i++) {
            if (this.keyframes.get(i - 1).frameIndex() == this.keyframes.get(i).frameIndex()) {
                throw new IllegalArgumentException("duplicate keyframe frameIndex: " + this.keyframes.get(i).frameIndex());
            }
        }
    }

    public static KeyframeCurve constant(float value) {
        return new KeyframeCurve(List.of(new Keyframe(0L, value, Easing.HOLD)));
    }

    public float valueAt(long frameIndex) {
        if (frameIndex <= keyframes.getFirst().frameIndex()) return keyframes.getFirst().value();
        if (frameIndex >= keyframes.getLast().frameIndex()) return keyframes.getLast().value();

        for (int i = 1; i < keyframes.size(); i++) {
            Keyframe right = keyframes.get(i);
            if (frameIndex == right.frameIndex()) return right.value();
            if (frameIndex < right.frameIndex()) {
                Keyframe left = keyframes.get(i - 1);
                float position = (float)(frameIndex - left.frameIndex())
                        / (float)(right.frameIndex() - left.frameIndex());
                float eased = left.easing().apply(Math.max(0f, Math.min(1f, position)));
                return left.value() + (right.value() - left.value()) * eased;
            }
        }
        return keyframes.getLast().value();
    }

    public List<Keyframe> keyframes() {
        return keyframes;
    }
}
