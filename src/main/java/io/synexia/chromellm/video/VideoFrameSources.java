package io.synexia.chromellm.video;

import io.synexia.chromellm.video.nativebridge.JniFfmpegFrameSource;

import java.io.IOException;
import java.nio.file.Path;

public final class VideoFrameSources {
    private VideoFrameSources() {
    }

    public static VideoFrameSource open(
            Path input,
            DecoderBackend backend,
            String ffmpeg,
            FfmpegProbe probe) throws IOException, InterruptedException {
        return switch (backend) {
            case PROCESS -> new ProcessFfmpegFrameSource(input, ffmpeg, probe);
            case JNI -> new JniFfmpegFrameSource(input);
            case AUTO -> auto(input, ffmpeg, probe);
        };
    }

    private static VideoFrameSource auto(
            Path input,
            String ffmpeg,
            FfmpegProbe probe) throws IOException, InterruptedException {
        try {
            return new JniFfmpegFrameSource(input);
        } catch (Throwable ignored) {
            return new ProcessFfmpegFrameSource(input, ffmpeg, probe);
        }
    }
}
