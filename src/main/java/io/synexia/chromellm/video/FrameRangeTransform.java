package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class FrameRangeTransform implements FrameTransform {
    private final long startInclusive;
    private final long endExclusive;
    private final FrameTransform delegate;

    public FrameRangeTransform(long startInclusive, long endExclusive, FrameTransform delegate) {
        if (startInclusive < 0L || endExclusive <= startInclusive) {
            throw new IllegalArgumentException("invalid frame range");
        }
        this.startInclusive = startInclusive;
        this.endExclusive = endExclusive;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        return frameIndex >= startInclusive && frameIndex < endExclusive
                ? delegate.apply(frame, frameIndex)
                : frame;
    }
}
