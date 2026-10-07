package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class StabilizationTransform implements FrameTransform, FrameTransformLifecycle {
    private final TranslationEstimator estimator;
    private final SceneCutDetector sceneCutDetector;
    private final double smoothing;
    private RgbaFrame previousSource;
    private double smoothedDx;
    private double smoothedDy;

    public StabilizationTransform(
            TranslationEstimator estimator,
            SceneCutDetector sceneCutDetector,
            double smoothing) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.sceneCutDetector = Objects.requireNonNull(sceneCutDetector, "sceneCutDetector");
        if (!Double.isFinite(smoothing) || smoothing < 0d || smoothing >= 1d) {
            throw new IllegalArgumentException("smoothing must be in [0,1)");
        }
        this.smoothing = smoothing;
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        Objects.requireNonNull(frame, "frame");
        if (previousSource == null || sceneCutDetector.isCut(previousSource, frame)) {
            previousSource = frame;
            smoothedDx = 0d;
            smoothedDy = 0d;
            return frame;
        }

        FrameTranslation translation = estimator.estimate(previousSource, frame);
        smoothedDx = smoothing * smoothedDx + (1d - smoothing) * translation.dx();
        smoothedDy = smoothing * smoothedDy + (1d - smoothing) * translation.dy();
        previousSource = frame;
        return FrameWarp.translate(frame, (int)Math.round(smoothedDx), (int)Math.round(smoothedDy));
    }

    @Override
    public void reset() {
        previousSource = null;
        smoothedDx = 0d;
        smoothedDy = 0d;
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
