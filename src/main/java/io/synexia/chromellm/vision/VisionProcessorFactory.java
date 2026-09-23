package io.synexia.chromellm.vision;

import io.synexia.chromellm.vision.nativebridge.OpenCvVisionProcessor;

public final class VisionProcessorFactory {
    public enum Backend {
        AUTO,
        JAVA,
        OPENCV
    }

    private VisionProcessorFactory() {
    }

    public static VisionProcessor open(Backend backend) {
        return switch (backend) {
            case JAVA -> new JavaVisionProcessor();
            case OPENCV -> new OpenCvVisionProcessor();
            case AUTO -> auto();
        };
    }

    public static VisionProcessor auto() {
        try {
            return new OpenCvVisionProcessor();
        } catch (Throwable ignored) {
            return new JavaVisionProcessor();
        }
    }
}
