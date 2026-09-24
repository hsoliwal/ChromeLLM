package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.RgbaFrame;

@FunctionalInterface
public interface FrameMaskProvider {
    AlphaMask maskFor(RgbaFrame frame, long frameIndex);
}
