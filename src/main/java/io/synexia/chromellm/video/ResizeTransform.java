package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

public final class ResizeTransform implements FrameTransform, FrameTransformShape {
    private final int width;
    private final int height;

    public ResizeTransform(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("resize dimensions must be positive");
        this.width = width;
        this.height = height;
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        if (frame.width() == width && frame.height() == height) return frame;
        byte[] source = frame.pixels();
        byte[] output = new byte[width * height * 4];

        float scaleX = (float)frame.width() / width;
        float scaleY = (float)frame.height() / height;
        for (int y = 0; y < height; y++) {
            float sy = Math.max(0f, Math.min(frame.height() - 1f, (y + 0.5f) * scaleY - 0.5f));
            int y0 = (int)Math.floor(sy);
            int y1 = Math.min(frame.height() - 1, y0 + 1);
            float ty = sy - y0;
            for (int x = 0; x < width; x++) {
                float sx = Math.max(0f, Math.min(frame.width() - 1f, (x + 0.5f) * scaleX - 0.5f));
                int x0 = (int)Math.floor(sx);
                int x1 = Math.min(frame.width() - 1, x0 + 1);
                float tx = sx - x0;
                int dst = (y * width + x) * 4;
                for (int c = 0; c < 4; c++) {
                    float top = lerp(value(source, frame.width(), x0, y0, c), value(source, frame.width(), x1, y0, c), tx);
                    float bottom = lerp(value(source, frame.width(), x0, y1, c), value(source, frame.width(), x1, y1, c), tx);
                    output[dst + c] = (byte)Math.round(lerp(top, bottom, ty));
                }
            }
        }
        return new RgbaFrame(width, height, output);
    }

    @Override
    public VideoStreamInfo outputStreamInfo(VideoStreamInfo input) {
        return new VideoStreamInfo(width, height, input.framesPerSecond());
    }

    private static int value(byte[] pixels, int width, int x, int y, int channel) {
        return pixels[(y * width + x) * 4 + channel] & 255;
    }

    private static float lerp(float left, float right, float amount) {
        return left + (right - left) * amount;
    }
}
