package io.synexia.chromellm.video;

@FunctionalInterface
public interface FrameTransformShape {
    VideoStreamInfo outputStreamInfo(VideoStreamInfo input);
}
