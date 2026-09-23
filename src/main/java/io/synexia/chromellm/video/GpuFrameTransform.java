package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.gpu.PixelParameters;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class GpuFrameTransform implements FrameTransform {
    private final ImageProcessor processor;
    private final PixelOperation operation;
    private final PixelParameters parameters;

    public GpuFrameTransform(
            ImageProcessor processor,
            PixelOperation operation,
            PixelParameters parameters) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        return processor.process(frame, operation, parameters);
    }
}
