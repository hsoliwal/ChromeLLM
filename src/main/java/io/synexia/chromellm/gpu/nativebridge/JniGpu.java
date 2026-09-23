package io.synexia.chromellm.gpu.nativebridge;

final class JniGpu {
    static {
        System.loadLibrary(System.getProperty("chromellm.gpu.jni.library", "chromellm_gpu_jni"));
    }

    private JniGpu() {}

    static native long create(boolean allowCpuFallback);
    static native boolean hardwareAccelerated(long handle);
    static native String deviceName(long handle);
    static native byte[] process(long handle, int operation, byte[] input, int width, int height,
                                 float p0, float p1, float p2, float p3);
    static native byte[] blend(long handle, byte[] base, byte[] overlay, int width, int height, float opacity);
    static native byte[] generate(long handle, int generator, int width, int height, long seed,
                                  float p0, float p1, float p2, float p3);
    static native void destroy(long handle);
}
