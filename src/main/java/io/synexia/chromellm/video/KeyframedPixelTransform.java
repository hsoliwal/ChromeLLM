package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.gpu.PixelParameters;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class KeyframedPixelTransform implements FrameTransform {
    private final ImageProcessor processor;
    private final PixelOperation operation;
    private final KeyframeCurve p0;
    private final KeyframeCurve p1;
    private final KeyframeCurve p2;
    private final KeyframeCurve p3;

    public KeyframedPixelTransform(
            ImageProcessor processor,
            PixelOperation operation,
            KeyframeCurve p0,
            KeyframeCurve p1,
            KeyframeCurve p2,
            KeyframeCurve p3) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.p0 = Objects.requireNonNull(p0, "p0");
        this.p1 = Objects.requireNonNull(p1, "p1");
        this.p2 = Objects.requireNonNull(p2, "p2");
        this.p3 = Objects.requireNonNull(p3, "p3");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        return processor.process(
                frame,
                operation,
                new PixelParameters(
                        p0.valueAt(frameIndex),
                        p1.valueAt(frameIndex),
                        p2.valueAt(frameIndex),
                        p3.valueAt(frameIndex)));
    }
}
