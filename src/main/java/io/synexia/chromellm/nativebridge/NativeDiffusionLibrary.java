package io.synexia.chromellm.nativebridge;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public interface NativeDiffusionLibrary extends Library {
    NativeDiffusionLibrary INSTANCE = Native.load(System.getProperty("chromellm.diffusion.library", "chromellm_diffusion"), NativeDiffusionLibrary.class);

    Pointer diffusion_create(int width, int height, int channels, long seed);
    int diffusion_generate(Pointer handle, int conditionMode, float[] condition, int conditionLength,
                           int conditionWidth, int conditionHeight, int conditionChannels, int classId,
                           int steps, float guidanceScale, float eta, float[] output, int outputLength);
    void diffusion_destroy(Pointer handle);
    String diffusion_last_error();
}
