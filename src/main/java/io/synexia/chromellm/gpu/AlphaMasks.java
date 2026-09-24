package io.synexia.chromellm.gpu;

public final class AlphaMasks {
    private AlphaMasks() {
    }

    public static AlphaMask invert(AlphaMask mask) {
        byte[] source = mask.values();
        byte[] output = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            output[i] = (byte)(255 - (source[i] & 255));
        }
        return new AlphaMask(mask.width(), mask.height(), output);
    }

    public static AlphaMask threshold(AlphaMask mask, int threshold) {
        if (threshold < 0 || threshold > 255) throw new IllegalArgumentException("threshold must be between 0 and 255");
        byte[] source = mask.values();
        byte[] output = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            output[i] = (byte)((source[i] & 255) >= threshold ? 255 : 0);
        }
        return new AlphaMask(mask.width(), mask.height(), output);
    }

    public static AlphaMask feather(AlphaMask mask, int radius) {
        if (radius < 1 || radius > 64) throw new IllegalArgumentException("radius must be between 1 and 64");
        byte[] source = mask.values();
        byte[] horizontal = new byte[source.length];
        byte[] output = new byte[source.length];
        blurHorizontal(source, horizontal, mask.width(), mask.height(), radius);
        blurVertical(horizontal, output, mask.width(), mask.height(), radius);
        return new AlphaMask(mask.width(), mask.height(), output);
    }

    private static void blurHorizontal(byte[] source, byte[] output, int width, int height, int radius) {
        for (int y = 0; y < height; y++) {
            int sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += source[y * width + clamp(x, 0, width - 1)] & 255;
            }
            int window = radius * 2 + 1;
            for (int x = 0; x < width; x++) {
                output[y * width + x] = (byte)(sum / window);
                int remove = clamp(x - radius, 0, width - 1);
                int add = clamp(x + radius + 1, 0, width - 1);
                sum += (source[y * width + add] & 255) - (source[y * width + remove] & 255);
            }
        }
    }

    private static void blurVertical(byte[] source, byte[] output, int width, int height, int radius) {
        int window = radius * 2 + 1;
        for (int x = 0; x < width; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += source[clamp(y, 0, height - 1) * width + x] & 255;
            }
            for (int y = 0; y < height; y++) {
                output[y * width + x] = (byte)(sum / window);
                int remove = clamp(y - radius, 0, height - 1);
                int add = clamp(y + radius + 1, 0, height - 1);
                sum += (source[add * width + x] & 255) - (source[remove * width + x] & 255);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
