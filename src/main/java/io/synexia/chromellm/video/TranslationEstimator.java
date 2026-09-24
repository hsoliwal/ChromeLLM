package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

@FunctionalInterface
public interface TranslationEstimator {
    FrameTranslation estimate(RgbaFrame reference, RgbaFrame current);
}
