package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class MaskedTransform implements FrameTransform, FrameTransformLifecycle {
    private final FrameTransform delegate;
    private final FrameMaskProvider maskProvider;

    public MaskedTransform(FrameTransform delegate, FrameMaskProvider maskProvider) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.maskProvider = Objects.requireNonNull(maskProvider, "maskProvider");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        RgbaFrame transformed = Objects.requireNonNull(delegate.apply(frame, frameIndex), "delegate returned null");
        if (transformed.width() != frame.width() || transformed.height() != frame.height()) {
            throw new IllegalStateException("masked transform changed dimensions");
        }

        AlphaMask mask = Objects.requireNonNull(maskProvider.maskFor(frame, frameIndex), "mask provider returned null");
        if (mask.width() != frame.width() || mask.height() != frame.height()) {
            throw new IllegalArgumentException("mask dimensions differ from frame");
        }

        byte[] base = frame.pixels();
        byte[] overlay = transformed.pixels();
        byte[] alpha = mask.values();
        byte[] output = new byte[base.length];

        for (int p = 0; p < alpha.length; p++) {
            float weight = (alpha[p] & 255) / 255f;
            int i = p * 4;
            for (int c = 0; c < 4; c++) {
                output[i + c] = (byte)Math.round(
                        (base[i + c] & 255) * (1f - weight)
                                + (overlay[i + c] & 255) * weight);
            }
        }
        return new RgbaFrame(frame.width(), frame.height(), output);
    }

    @Override
    public void onStreamStart(VideoStreamInfo streamInfo) {
        if (delegate instanceof FrameTransformShape shape) {
            VideoStreamInfo output = shape.outputStreamInfo(streamInfo);
            if (output.width() != streamInfo.width() || output.height() != streamInfo.height()) {
                throw new IllegalArgumentException(
                        "masked delegate must preserve frame dimensions");
            }
        }
        if (delegate instanceof FrameTransformLifecycle lifecycle) lifecycle.onStreamStart(streamInfo);
    }

    @Override
    public void reset() {
        if (delegate instanceof FrameTransformLifecycle lifecycle) lifecycle.reset();
    }

    @Override
    public void onStreamEnd() {
        if (delegate instanceof FrameTransformLifecycle lifecycle) lifecycle.onStreamEnd();
    }
}
