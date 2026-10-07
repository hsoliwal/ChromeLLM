package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

public final class FrameWarp {
    private FrameWarp() {
    }

    public static RgbaFrame translate(RgbaFrame frame, int sampleDx, int sampleDy) {
        byte[] source = frame.pixels();
        byte[] output = new byte[source.length];
        int width = frame.width();
        int height = frame.height();

        for (int y = 0; y < height; y++) {
            int sy = Math.max(0, Math.min(height - 1, y + sampleDy));
            for (int x = 0; x < width; x++) {
                int sx = Math.max(0, Math.min(width - 1, x + sampleDx));
                int src = (sy * width + sx) * 4;
                int dst = (y * width + x) * 4;
                output[dst] = source[src];
                output[dst + 1] = source[src + 1];
                output[dst + 2] = source[src + 2];
                output[dst + 3] = source[src + 3];
            }
        }
        return new RgbaFrame(width, height, output);
    }
}
