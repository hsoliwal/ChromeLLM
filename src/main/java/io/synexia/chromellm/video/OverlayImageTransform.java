package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.ObjectComposer;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class OverlayImageTransform implements FrameTransform {
    private final ImageProcessor processor;
    private final RgbaFrame overlay;
    private final int x;
    private final int y;
    private final float opacity;

    public OverlayImageTransform(
            ImageProcessor processor,
            RgbaFrame overlay,
            int x,
            int y,
            float opacity) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        if (!Float.isFinite(opacity) || opacity < 0f || opacity > 1f) {
            throw new IllegalArgumentException("opacity must be between 0 and 1");
        }
        this.x = x;
        this.y = y;
        this.opacity = opacity;
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        return ObjectComposer.place(processor, frame, overlay, x, y, opacity);
    }
}
