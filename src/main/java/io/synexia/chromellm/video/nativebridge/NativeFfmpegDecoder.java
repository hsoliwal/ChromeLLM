package io.synexia.chromellm.video.nativebridge;

final class NativeFfmpegDecoder {
    static {
        System.loadLibrary(System.getProperty("chromellm.ffmpeg.jni.library", "chromellm_ffmpeg_jni"));
    }

    private NativeFfmpegDecoder() {
    }

    static native long open(String path);

    static native int width(long handle);

    static native int height(long handle);

    static native double framesPerSecond(long handle);

    static native int nextRgba(long handle, byte[] output);

    static native int seekMillis(long handle, long millis);

    static native String version();

    static native void close(long handle);
}
