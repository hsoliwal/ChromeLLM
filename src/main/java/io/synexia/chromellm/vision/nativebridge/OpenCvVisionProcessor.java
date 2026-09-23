package io.synexia.chromellm.vision.nativebridge;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.vision.VisionProcessor;

public final class OpenCvVisionProcessor implements VisionProcessor {
    private final String version;

    public OpenCvVisionProcessor() {
        this.version = OpenCvJni.version();
    }

    @Override
    public RgbaFrame inpaint(RgbaFrame image, AlphaMask mask, float radius) {
        requireSameSize(image, mask);
        return new RgbaFrame(
                image.width(),
                image.height(),
                OpenCvJni.inpaint(
                        image.pixels(),
                        mask.values(),
                        image.width(),
                        image.height(),
                        radius));
    }

    @Override
    public RgbaFrame seamlessClone(RgbaFrame background, RgbaFrame object, int centerX, int centerY) {
        return new RgbaFrame(
                background.width(),
                background.height(),
                OpenCvJni.seamlessClone(
                        background.pixels(),
                        background.width(),
                        background.height(),
                        object.pixels(),
                        object.width(),
                        object.height(),
                        centerX,
                        centerY));
    }

    @Override
    public AlphaMask grabCut(RgbaFrame image, int x, int y, int width, int height, int iterations) {
        return new AlphaMask(
                image.width(),
                image.height(),
                OpenCvJni.grabCut(
                        image.pixels(),
                        image.width(),
                        image.height(),
                        x,
                        y,
                        width,
                        height,
                        iterations));
    }

    @Override
    public RgbaFrame interpolate(RgbaFrame previous, RgbaFrame next, float position) {
        requireSameSize(previous, next);
        return new RgbaFrame(
                previous.width(),
                previous.height(),
                OpenCvJni.interpolate(
                        previous.pixels(),
                        next.pixels(),
                        previous.width(),
                        previous.height(),
                        position));
    }

    @Override
    public String backendName() {
        return "opencv-jni:" + version;
    }

    private static void requireSameSize(RgbaFrame left, RgbaFrame right) {
        if (left.width() != right.width() || left.height() != right.height()) {
            throw new IllegalArgumentException("frame dimensions differ");
        }
    }

    private static void requireSameSize(RgbaFrame image, AlphaMask mask) {
        if (image.width() != mask.width() || image.height() != mask.height()) {
            throw new IllegalArgumentException("mask dimensions differ from image");
        }
    }
}
