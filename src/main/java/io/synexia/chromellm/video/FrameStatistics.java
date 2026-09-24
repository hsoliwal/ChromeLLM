package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

public final class FrameStatistics {
    private static final int HISTOGRAM_BINS = 64;

    private FrameStatistics() {
    }

    public static double averageLuma(RgbaFrame frame) {
        byte[] pixels = frame.pixels();
        double sum = 0d;
        for (int i = 0; i < pixels.length; i += 4) {
            sum += luma(pixels, i);
        }
        return sum / frame.pixelCount() / 255d;
    }

    public static double meanAbsoluteLumaDifference(RgbaFrame first, RgbaFrame second, int stride) {
        requireSameDimensions(first, second);
        int sampleStride = Math.max(1, stride);
        byte[] a = first.pixels();
        byte[] b = second.pixels();
        double sum = 0d;
        long count = 0L;
        for (int y = 0; y < first.height(); y += sampleStride) {
            for (int x = 0; x < first.width(); x += sampleStride) {
                int i = (y * first.width() + x) * 4;
                sum += Math.abs(luma(a, i) - luma(b, i));
                count++;
            }
        }
        return count == 0L ? 0d : sum / count / 255d;
    }

    public static double histogramDistance(RgbaFrame first, RgbaFrame second, int stride) {
        requireSameDimensions(first, second);
        long[] a = histogram(first, stride);
        long[] b = histogram(second, stride);
        long aTotal = sum(a);
        long bTotal = sum(b);
        if (aTotal == 0L || bTotal == 0L) return 0d;

        double distance = 0d;
        for (int i = 0; i < HISTOGRAM_BINS; i++) {
            distance += Math.abs((double)a[i] / aTotal - (double)b[i] / bTotal);
        }
        return distance / 2d;
    }

    private static long[] histogram(RgbaFrame frame, int stride) {
        int sampleStride = Math.max(1, stride);
        long[] bins = new long[HISTOGRAM_BINS];
        byte[] pixels = frame.pixels();
        for (int y = 0; y < frame.height(); y += sampleStride) {
            for (int x = 0; x < frame.width(); x += sampleStride) {
                int i = (y * frame.width() + x) * 4;
                int value = Math.max(0, Math.min(255, Math.round(luma(pixels, i))));
                bins[Math.min(HISTOGRAM_BINS - 1, value * HISTOGRAM_BINS / 256)]++;
            }
        }
        return bins;
    }

    private static long sum(long[] values) {
        long total = 0L;
        for (long value : values) total += value;
        return total;
    }

    private static float luma(byte[] pixels, int i) {
        return 0.2126f * (pixels[i] & 255)
                + 0.7152f * (pixels[i + 1] & 255)
                + 0.0722f * (pixels[i + 2] & 255);
    }

    private static void requireSameDimensions(RgbaFrame first, RgbaFrame second) {
        if (first.width() != second.width() || first.height() != second.height()) {
            throw new IllegalArgumentException("frame dimensions differ");
        }
    }
}
