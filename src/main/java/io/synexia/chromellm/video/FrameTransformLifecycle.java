package io.synexia.chromellm.video;

public interface FrameTransformLifecycle {
    default void onStreamStart(VideoStreamInfo streamInfo) {
    }

    default void reset() {
    }

    default void onStreamEnd() {
    }
}
