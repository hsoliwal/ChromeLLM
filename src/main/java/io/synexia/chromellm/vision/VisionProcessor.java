package io.synexia.chromellm.vision;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.RgbaFrame;

public interface VisionProcessor extends AutoCloseable {
    RgbaFrame inpaint(RgbaFrame image, AlphaMask mask, float radius);

    RgbaFrame seamlessClone(RgbaFrame background, RgbaFrame object, int centerX, int centerY);

    AlphaMask grabCut(RgbaFrame image, int x, int y, int width, int height, int iterations);

    RgbaFrame interpolate(RgbaFrame previous, RgbaFrame next, float position);

    String backendName();

    @Override
    default void close() {
    }
}
