package io.synexia.chromellm.gpu;

public final class MaskOps {
    private MaskOps() {}

    public static RgbaFrame removeApprox(
            ImageProcessor processor,
            RgbaFrame image,
            AlphaMask mask,
            int blurRadius,
            int passes) {
        if (image.width() != mask.width() || image.height() != mask.height()) {
            throw new IllegalArgumentException("mask dimensions differ from image");
        }

        RgbaFrame fill = image;
        int safePasses = Math.max(1, Math.min(16, passes));
        int radius = Math.max(1, Math.min(8, blurRadius));
        for (int pass = 0; pass < safePasses; pass++) {
            fill = processor.process(fill, PixelOperation.BOX_BLUR, PixelParameters.blurRadius(radius));
        }

        byte[] replacement = fill.pixels();
        byte[] maskValues = mask.values();
        for (int p = 0; p < maskValues.length; p++) {
            replacement[p * 4 + 3] = maskValues[p];
        }

        return processor.blend(
                image,
                new RgbaFrame(image.width(), image.height(), replacement),
                1f);
    }
}
