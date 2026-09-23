package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class FfmpegVideoProcessor {
    private final String ffmpeg;
    private final FfmpegProbe probe;
    private final DecoderBackend decoderBackend;

    public FfmpegVideoProcessor() {
        this(
                System.getProperty("chromellm.ffmpeg", "ffmpeg"),
                new FfmpegProbe(),
                DecoderBackend.AUTO);
    }

    public FfmpegVideoProcessor(String ffmpeg, FfmpegProbe probe) {
        this(ffmpeg, probe, DecoderBackend.AUTO);
    }

    public FfmpegVideoProcessor(
            String ffmpeg,
            FfmpegProbe probe,
            DecoderBackend decoderBackend) {
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.decoderBackend = Objects.requireNonNull(decoderBackend, "decoderBackend");
    }

    public VideoProcessResult process(
            Path input,
            Path output,
            ImageProcessor processor,
            FrameTransform transform) throws IOException, InterruptedException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(transform, "transform");

        Instant start = Instant.now();
        long frames = 0L;
        String sourceBackend;

        try (VideoFrameSource source = VideoFrameSources.open(
                input,
                decoderBackend,
                ffmpeg,
                probe)) {
            VideoStreamInfo info = source.streamInfo();
            sourceBackend = source.backendName();

            try (FfmpegRgbaEncoder encoder = new FfmpegRgbaEncoder(
                    ffmpeg,
                    input,
                    output,
                    info)) {
                while (true) {
                    RgbaFrame frame = source.nextFrame();
                    if (frame == null) break;

                    RgbaFrame result = Objects.requireNonNull(
                            transform.apply(frame, frames),
                            "frame transform returned null");
                    if (result.width() != info.width() || result.height() != info.height()) {
                        throw new IllegalStateException("frame transform changed dimensions");
                    }

                    encoder.write(result);
                    frames++;
                }
            }

            return new VideoProcessResult(
                    output,
                    frames,
                    info,
                    Duration.between(start, Instant.now()),
                    sourceBackend + " -> " + processor.backendName());
        }
    }

}
