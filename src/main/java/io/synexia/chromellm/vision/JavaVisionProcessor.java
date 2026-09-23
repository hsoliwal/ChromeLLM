package io.synexia.chromellm.vision;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.CpuImageProcessor;
import io.synexia.chromellm.gpu.MaskOps;
import io.synexia.chromellm.gpu.ObjectComposer;
import io.synexia.chromellm.gpu.RgbaFrame;

public final class JavaVisionProcessor implements VisionProcessor {
    private final CpuImageProcessor processor = new CpuImageProcessor();

    @Override
    public RgbaFrame inpaint(RgbaFrame image, AlphaMask mask, float radius) {
        return MaskOps.removeApprox(
                processor,
                image,
                mask,
                Math.max(1, Math.round(radius)),
                4);
    }

    @Override
    public RgbaFrame seamlessClone(RgbaFrame background, RgbaFrame object, int centerX, int centerY) {
        int x = centerX - object.width() / 2;
        int y = centerY - object.height() / 2;
        return ObjectComposer.place(processor, background, object, x, y, 1f);
    }

    @Override
    public AlphaMask grabCut(RgbaFrame image, int x, int y, int width, int height, int iterations) {
        byte[] mask = new byte[image.pixelCount()];
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(image.width(), x + width);
        int bottom = Math.min(image.height(), y + height);
        for (int yy = top; yy < bottom; yy++) {
            for (int xx = left; xx < right; xx++) {
                mask[yy * image.width() + xx] = (byte)255;
            }
        }
        return new AlphaMask(image.width(), image.height(), mask);
    }

    @Override
    public RgbaFrame interpolate(RgbaFrame previous, RgbaFrame next, float position) {
        if (previous.width() != next.width() || previous.height() != next.height()) {
            throw new IllegalArgumentException("frame dimensions differ");
        }
        return processor.blend(previous, next, Math.max(0f, Math.min(1f, position)));
    }

    @Override
    public String backendName() {
        return "java-vision-fallback";
    }
}
