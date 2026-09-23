package io.synexia.chromellm.gpu;

import java.util.Arrays;
import java.util.Objects;

public final class RgbaFrame {
    private final int width;
    private final int height;
    private final byte[] rgba;

    public RgbaFrame(int width, int height, byte[] rgba) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be positive");
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
            throw new IllegalArgumentException("RGBA buffer length does not match dimensions");
        }
        this.width = width;
        this.height = height;
        this.rgba = Arrays.copyOf(rgba, rgba.length);
    }

    public int width() { return width; }
    public int height() { return height; }
    public int pixelCount() { return Math.multiplyExact(width, height); }
    public int byteSize() { return rgba.length; }
    public byte[] pixels() { return Arrays.copyOf(rgba, rgba.length); }
}
