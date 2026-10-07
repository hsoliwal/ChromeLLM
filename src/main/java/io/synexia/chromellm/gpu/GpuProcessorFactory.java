package io.synexia.chromellm.gpu;

import io.synexia.chromellm.gpu.nativebridge.JnaOpenClImageProcessor;
import io.synexia.chromellm.gpu.nativebridge.JniOpenClImageProcessor;

public final class GpuProcessorFactory {
    public enum Backend { AUTO, CPU, JNA, JNI }

    private GpuProcessorFactory() {}

    public static ImageProcessor open(Backend backend) {
        return switch(backend) {
            case CPU -> new CpuImageProcessor();
            case JNA -> new JnaOpenClImageProcessor(false);
            case JNI -> new JniOpenClImageProcessor(false);
            case AUTO -> auto();
        };
    }

    public static ImageProcessor auto() {
        try {
            return new JnaOpenClImageProcessor(false);
        } catch (Throwable ignored) {
            try {
                return new JniOpenClImageProcessor(false);
            } catch (Throwable ignoredAgain) {
                return new CpuImageProcessor();
            }
        }
    }
}
