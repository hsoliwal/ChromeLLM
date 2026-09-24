package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

public final class BlockMatchingTranslationEstimator implements TranslationEstimator {
    private final int searchRadius;
    private final int sampleStride;

    public BlockMatchingTranslationEstimator(int searchRadius, int sampleStride) {
        if (searchRadius < 0 || searchRadius > 64) {
            throw new IllegalArgumentException("searchRadius must be between 0 and 64");
        }
        if (sampleStride < 1) throw new IllegalArgumentException("sampleStride must be positive");
        this.searchRadius = searchRadius;
        this.sampleStride = sampleStride;
    }

    @Override
    public FrameTranslation estimate(RgbaFrame reference, RgbaFrame current) {
        if (reference.width() != current.width() || reference.height() != current.height()) {
            throw new IllegalArgumentException("frame dimensions differ");
        }

        byte[] a = reference.pixels();
        byte[] b = current.pixels();
        int width = reference.width();
        int height = reference.height();

        double best = Double.POSITIVE_INFINITY;
        int bestDx = 0;
        int bestDy = 0;

        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                double score = score(a, b, width, height, dx, dy);
                if (score < best) {
                    best = score;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }

        return new FrameTranslation(bestDx, bestDy, best);
    }

    private double score(byte[] reference, byte[] current, int width, int height, int dx, int dy) {
        int minX = Math.max(0, -dx);
        int maxX = Math.min(width, width - dx);
        int minY = Math.max(0, -dy);
        int maxY = Math.min(height, height - dy);

        double sum = 0d;
        long count = 0L;
        for (int y = minY; y < maxY; y += sampleStride) {
            for (int x = minX; x < maxX; x += sampleStride) {
                int referenceIndex = (y * width + x) * 4;
                int currentIndex = ((y + dy) * width + (x + dx)) * 4;
                sum += Math.abs(luma(reference, referenceIndex) - luma(current, currentIndex));
                count++;
            }
        }
        return count == 0L ? 1d : sum / count / 255d;
    }

    private static float luma(byte[] pixels, int i) {
        return 0.2126f * (pixels[i] & 255)
                + 0.7152f * (pixels[i + 1] & 255)
                + 0.0722f * (pixels[i + 2] & 255);
    }

    public int searchRadius() {
        return searchRadius;
    }

    public int sampleStride() {
        return sampleStride;
    }
}
