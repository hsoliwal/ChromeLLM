package io.synexia.chromellm.nativebridge;

final class JniDiffusion {
    static { System.loadLibrary(System.getProperty("chromellm.diffusion.jni.library", "chromellm_diffusion_jni")); }
    private JniDiffusion() {}
    static native float[] generate(int width, int height, int channels, int steps, long seed,
                                   float guidanceScale, float eta, int conditionMode, float[] condition,
                                   int conditionWidth, int conditionHeight, int conditionChannels, int classId);
}
