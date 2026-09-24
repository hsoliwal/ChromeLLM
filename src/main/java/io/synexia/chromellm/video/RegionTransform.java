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
        if (delegate instanceof FrameTransformLifecycle lifecycle) {
            FrameRegion effective = region.clampTo(streamInfo.width(), streamInfo.height());
            lifecycle.onStreamStart(new VideoStreamInfo(
                    effective.width(),
                    effective.height(),
                    streamInfo.framesPerSecond()));
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
