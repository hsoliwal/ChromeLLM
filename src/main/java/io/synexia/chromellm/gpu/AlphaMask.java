package io.synexia.chromellm.gpu;

import java.util.Arrays;
import java.util.Objects;

public final class AlphaMask {
    private final int width;
    private final int height;
    private final byte[] alpha;

    public AlphaMask(int width, int height, byte[] alpha) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be positive");
        Objects.requireNonNull(alpha, "alpha");
        if (alpha.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("mask length does not match dimensions");
        }
        this.width = width;
        this.height = height;
        this.alpha = Arrays.copyOf(alpha, alpha.length);
    }

    public int width() { return width; }
    public int height() { return height; }
    public byte[] values() { return Arrays.copyOf(alpha, alpha.length); }

    public static AlphaMask fromFrameLuma(RgbaFrame frame) {
        byte[] rgba = frame.pixels();
        byte[] alpha = new byte[frame.pixelCount()];
        for (int p = 0; p < alpha.length; p++) {
            int i = p * 4;
            int y = Math.round(0.2126f * (rgba[i] & 255)
                    + 0.7152f * (rgba[i + 1] & 255)
                    + 0.0722f * (rgba[i + 2] & 255));
            alpha[p] = (byte)y;
        }
        return new AlphaMask(frame.width(), frame.height(), alpha);
    }
}
