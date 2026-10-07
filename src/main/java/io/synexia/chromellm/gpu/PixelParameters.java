package io.synexia.chromellm.gpu;

public record PixelParameters(float p0, float p1, float p2, float p3) {
    public PixelParameters {
        if (!Float.isFinite(p0) || !Float.isFinite(p1) || !Float.isFinite(p2) || !Float.isFinite(p3)) {
            throw new IllegalArgumentException("pixel parameters must be finite");
        }
    }

    public static PixelParameters none() { return new PixelParameters(0f, 0f, 0f, 0f); }
    public static PixelParameters brightnessContrast(float brightness, float contrast) {
        return new PixelParameters(brightness, contrast, 0f, 0f);
    }
    public static PixelParameters gamma(float gamma) { return new PixelParameters(gamma, 0f, 0f, 0f); }
    public static PixelParameters threshold(float threshold) { return new PixelParameters(threshold, 0f, 0f, 0f); }
    public static PixelParameters channelScale(float red, float green, float blue, float alpha) {
        return new PixelParameters(red, green, blue, alpha);
    }
    public static PixelParameters blurRadius(int radius) { return new PixelParameters(radius, 0f, 0f, 0f); }
}
