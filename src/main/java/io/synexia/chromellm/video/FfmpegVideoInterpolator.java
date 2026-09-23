package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.vision.VisionProcessor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class FfmpegVideoInterpolator {
    private final String ffmpeg;
    private final FfmpegProbe probe;
    private final DecoderBackend decoderBackend;

    public FfmpegVideoInterpolator() {
        this(
                System.getProperty("chromellm.ffmpeg", "ffmpeg"),
                new FfmpegProbe(),
                DecoderBackend.AUTO);
    }

    public FfmpegVideoInterpolator(
            String ffmpeg,
            FfmpegProbe probe,
            DecoderBackend decoderBackend) {
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.decoderBackend = Objects.requireNonNull(decoderBackend, "decoderBackend");
    }

    public VideoProcessResult interpolate(
            Path input,
            Path output,
            VisionProcessor vision,
            int factor) throws IOException, InterruptedException {
        if (factor < 2 || factor > 8) {
            throw new IllegalArgumentException("interpolation factor must be between 2 and 8");
        }
        Objects.requireNonNull(vision, "vision");

        Instant start = Instant.now();
        long written = 0L;

        try (VideoFrameSource source = VideoFrameSources.open(
                input,
                decoderBackend,
                ffmpeg,
                probe)) {
            VideoStreamInfo sourceInfo = source.streamInfo();
            VideoStreamInfo outputInfo = new VideoStreamInfo(
                    sourceInfo.width(),
                    sourceInfo.height(),
                    sourceInfo.framesPerSecond() * factor);

            try (FfmpegRgbaEncoder encoder = new FfmpegRgbaEncoder(
                    ffmpeg,
                    input,
                    output,
                    outputInfo)) {
                RgbaFrame previous = source.nextFrame();
                if (previous == null) {
                    return new VideoProcessResult(
                            output,
                            0,
                            outputInfo,
                            Duration.between(start, Instant.now()),
                            source.backendName() + " -> " + vision.backendName());
                }

                encoder.write(previous);
                written++;

                while (true) {
                    RgbaFrame next = source.nextFrame();
                    if (next == null) break;

                    for (int step = 1; step < factor; step++) {
                        float position = (float)step / factor;
                        encoder.write(vision.interpolate(previous, next, position));
                        written++;
                    }

                    encoder.write(next);
                    written++;
                    previous = next;
                }
            }

            return new VideoProcessResult(
                    output,
                    written,
                    outputInfo,
                    Duration.between(start, Instant.now()),
                    source.backendName() + " -> " + vision.backendName());
        }
    }
}
