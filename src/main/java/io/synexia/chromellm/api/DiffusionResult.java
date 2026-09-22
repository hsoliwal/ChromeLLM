package io.synexia.chromellm.api;

import java.util.Arrays;
import java.util.Map;

public record DiffusionResult(int width, int height, int channels, float[] pixels, long seed, Map<String,String> metadata) {
    public DiffusionResult {
        pixels = Arrays.copyOf(pixels, pixels.length);
        metadata = Map.copyOf(metadata);
        if (pixels.length != width * height * channels) throw new IllegalArgumentException("pixel buffer does not match dimensions");
    }
    @Override public float[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
    public byte[] rgb8() {
        if (channels < 3) throw new IllegalStateException("RGB conversion requires at least 3 channels");
        byte[] output = new byte[width * height * 3];
        for (int p = 0; p < width * height; p++) {
            for (int c = 0; c < 3; c++) {
                float value = Math.max(0f, Math.min(1f, pixels[p * channels + c]));
                output[p * 3 + c] = (byte)Math.round(value * 255f);
            }
        }
        return output;
    }
}
