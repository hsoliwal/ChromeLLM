package io.synexia.chromellm.gpu.nativebridge;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public interface NativeGpuLibrary extends Library {
    NativeGpuLibrary INSTANCE = Native.load(
            System.getProperty("chromellm.gpu.library", "chromellm_gpu"),
            NativeGpuLibrary.class);

    Pointer gpu_create(int allowCpuFallback);
    int gpu_hardware_accelerated(Pointer handle);
    String gpu_device_name(Pointer handle);
    int gpu_process_rgba8(Pointer handle, int operation, byte[] input, byte[] output,
                          int width, int height, float p0, float p1, float p2, float p3);
    int gpu_blend_rgba8(Pointer handle, byte[] base, byte[] overlay, byte[] output,
                        int width, int height, float opacity);
    int gpu_generate_rgba8(Pointer handle, int generator, byte[] output, int width, int height,
                           long seed, float p0, float p1, float p2, float p3);
    void gpu_destroy(Pointer handle);
    String gpu_last_error();
}
