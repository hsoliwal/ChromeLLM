package io.synexia.chromellm.video;

import java.util.List;
import java.util.Objects;

public record VideoEncodingOptions(
        VideoCodec videoCodec,
        String preset,
        int crf,
        String pixelFormat,
        AudioMode audioMode,
        int audioBitrateKbps,
        int threads,
        List<String> extraArguments) {

    public VideoEncodingOptions {
        videoCodec = Objects.requireNonNullElse(videoCodec, VideoCodec.H264);
        preset = normalize(preset, "veryfast");
        if (crf < 0 || crf > 63) throw new IllegalArgumentException("crf must be between 0 and 63");
        pixelFormat = normalize(pixelFormat, "yuv420p");
        audioMode = Objects.requireNonNullElse(audioMode, AudioMode.COPY);
        if (audioBitrateKbps < 32 || audioBitrateKbps > 1536) {
            throw new IllegalArgumentException("audioBitrateKbps must be between 32 and 1536");
        }
        if (threads < 0 || threads > 256) throw new IllegalArgumentException("threads must be between 0 and 256");
        extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
        for (String argument : extraArguments) {
            if (argument == null || argument.isBlank()) {
                throw new IllegalArgumentException("extra arguments must be non-blank");
            }
        }
    }

    public static VideoEncodingOptions defaults() {
        return new VideoEncodingOptions(
                VideoCodec.H264,
                "veryfast",
                18,
                "yuv420p",
                AudioMode.COPY,
                192,
                0,
                List.of());
    }

    public VideoEncodingOptions withVideoCodec(VideoCodec codec) {
        return new VideoEncodingOptions(codec, preset, crf, pixelFormat, audioMode, audioBitrateKbps, threads, extraArguments);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
