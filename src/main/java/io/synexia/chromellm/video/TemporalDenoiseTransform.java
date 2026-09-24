package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class TemporalDenoiseTransform implements FrameTransform, FrameTransformLifecycle {
    private final ImageProcessor processor;
    private final float currentFrameWeight;
    private final SceneCutDetector sceneCutDetector;
    private RgbaFrame previousOutput;
    private RgbaFrame previousSource;

    public TemporalDenoiseTransform(
            ImageProcessor processor,
            float currentFrameWeight,
            SceneCutDetector sceneCutDetector) {
        this.processor = Objects.requireNonNull(processor, "processor");
        if (!Float.isFinite(currentFrameWeight) || currentFrameWeight <= 0f || currentFrameWeight > 1f) {
            throw new IllegalArgumentException("currentFrameWeight must be in (0,1]");
        }
        this.currentFrameWeight = currentFrameWeight;
        this.sceneCutDetector = Objects.requireNonNull(sceneCutDetector, "sceneCutDetector");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        Objects.requireNonNull(frame, "frame");
        if (previousOutput == null
                || previousSource == null
                || sceneCutDetector.isCut(previousSource, frame)) {
            previousSource = frame;
            previousOutput = frame;
            return frame;
        }

        RgbaFrame filtered = processor.blend(previousOutput, frame, currentFrameWeight);
        previousSource = frame;
        previousOutput = filtered;
        return filtered;
    }

    @Override
    public void reset() {
        previousOutput = null;
        previousSource = null;
    }

    @Override
    public void onStreamStart(VideoStreamInfo streamInfo) {
        reset();
    }

    @Override
    public void onStreamEnd() {
        reset();
    }
}
