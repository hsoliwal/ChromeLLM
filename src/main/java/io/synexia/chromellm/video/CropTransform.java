package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class CropTransform implements FrameTransform, FrameTransformShape {
    private final FrameRegion region;

    public CropTransform(FrameRegion region) {
        this.region = Objects.requireNonNull(region, "region");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        return FrameRegions.crop(frame, region);
    }

    @Override
    public VideoStreamInfo outputStreamInfo(VideoStreamInfo input) {
        FrameRegion effective = region.clampTo(input.width(), input.height());
        return new VideoStreamInfo(effective.width(), effective.height(), input.framesPerSecond());
    }
}
