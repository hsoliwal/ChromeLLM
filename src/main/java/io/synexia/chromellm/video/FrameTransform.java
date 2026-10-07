package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

@FunctionalInterface
public interface FrameTransform {
    RgbaFrame apply(RgbaFrame frame, long frameIndex);
}
