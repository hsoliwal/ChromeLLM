package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.gpu.PixelParameters;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class UnsharpMaskTransform implements FrameTransform {
    private final ImageProcessor processor;
    private final int radius;
    private final float amount;
    private final int threshold;

    public UnsharpMaskTransform(ImageProcessor processor, int radius, float amount, int threshold) {
        this.processor = Objects.requireNonNull(processor, "processor");
        if (radius < 1 || radius > 8) throw new IllegalArgumentException("radius must be between 1 and 8");
        if (!Float.isFinite(amount) || amount < 0f || amount > 5f) {
            throw new IllegalArgumentException("amount must be between 0 and 5");
        }
        if (threshold < 0 || threshold > 255) throw new IllegalArgumentException("threshold must be between 0 and 255");
        this.radius = radius;
        this.amount = amount;
        this.threshold = threshold;
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        RgbaFrame blurred = processor.process(
                frame,
                PixelOperation.BOX_BLUR,
                PixelParameters.blurRadius(radius));
        byte[] source = frame.pixels();
        byte[] smooth = blurred.pixels();
        byte[] output = source.clone();

        for (int i = 0; i < output.length; i += 4) {
            for (int c = 0; c < 3; c++) {
                int original = source[i + c] & 255;
                int blurredValue = smooth[i + c] & 255;
                int delta = original - blurredValue;
                if (Math.abs(delta) >= threshold) {
                    output[i + c] = (byte)Math.round(clamp(original + amount * delta));
                }
            }
        }
        return new RgbaFrame(frame.width(), frame.height(), output);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(255f, value));
    }
}
