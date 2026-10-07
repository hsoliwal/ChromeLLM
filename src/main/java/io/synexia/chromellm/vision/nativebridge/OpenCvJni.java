package io.synexia.chromellm.vision.nativebridge;

final class OpenCvJni {
    static {
        System.loadLibrary(System.getProperty("chromellm.opencv.jni.library", "chromellm_opencv_jni"));
    }

    private OpenCvJni() {
    }

    static native byte[] inpaint(
            byte[] rgba,
            byte[] mask,
            int width,
            int height,
            float radius);

    static native byte[] seamlessClone(
            byte[] backgroundRgba,
            int backgroundWidth,
            int backgroundHeight,
            byte[] objectRgba,
            int objectWidth,
            int objectHeight,
            int centerX,
            int centerY);

    static native byte[] grabCut(
            byte[] rgba,
            int width,
            int height,
            int x,
            int y,
            int rectWidth,
            int rectHeight,
            int iterations);

    static native byte[] interpolate(
            byte[] previousRgba,
            byte[] nextRgba,
            int width,
            int height,
            float position);

    static native String version();
}
