package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class RegionTransform implements FrameTransform, FrameTransformLifecycle {
    private final FrameRegion region;
    private final FrameTransform delegate;

    public RegionTransform(FrameRegion region, FrameTransform delegate) {
        this.region = Objects.requireNonNull(region, "region");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        FrameRegion effective = region.clampTo(frame.width(), frame.height());
        RgbaFrame patch = FrameRegions.crop(frame, effective);
        RgbaFrame transformed = Objects.requireNonNull(delegate.apply(patch, frameIndex), "delegate returned null");
        if (transformed.width() != patch.width() || transformed.height() != patch.height()) {
            throw new IllegalStateException("region transform changed patch dimensions");
        }
        return FrameRegions.paste(frame, transformed, effective.x(), effective.y());
    }

    @Override
    public void onStreamStart(VideoStreamInfo streamInfo) {
        FrameRegion effective = region.clampTo(streamInfo.width(), streamInfo.height());
        VideoStreamInfo patchInfo = new VideoStreamInfo(
                effective.width(),
                effective.height(),
                streamInfo.framesPerSecond());
        rejectShapeChange(patchInfo);
        if (delegate instanceof FrameTransformLifecycle lifecycle) {
            lifecycle.onStreamStart(patchInfo);
        }
    }

    private void rejectShapeChange(VideoStreamInfo input) {
        if (delegate instanceof FrameTransformShape shape) {
            VideoStreamInfo output = shape.outputStreamInfo(input);
            if (output.width() != input.width() || output.height() != input.height()) {
                throw new IllegalArgumentException(
                        "region delegate must preserve patch dimensions");
            }
        }
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
