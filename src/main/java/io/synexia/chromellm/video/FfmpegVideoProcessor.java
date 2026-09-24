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
        return process(
                input,
                output,
                processor,
                transform,
                VideoEncodingOptions.defaults(),
                ProcessingProgressListener.NONE,
                ProcessingControl.NEVER_CANCELLED,
                30);
    }

    public VideoProcessResult process(
            Path input,
            Path output,
            ImageProcessor processor,
            FrameTransform transform,
            VideoEncodingOptions encodingOptions,
            ProcessingProgressListener progressListener,
            ProcessingControl control,
            int progressEveryFrames) throws IOException, InterruptedException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(encodingOptions, "encodingOptions");
        progressListener = Objects.requireNonNullElse(progressListener, ProcessingProgressListener.NONE);
        control = Objects.requireNonNullElse(control, ProcessingControl.NEVER_CANCELLED);
        if (progressEveryFrames < 1) throw new IllegalArgumentException("progressEveryFrames must be positive");

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
            FrameTransformLifecycle lifecycle = transform instanceof FrameTransformLifecycle value
                    ? value
                    : null;
            if (lifecycle != null) lifecycle.onStreamStart(info);

            try {
                try (FfmpegRgbaEncoder encoder = new FfmpegRgbaEncoder(
                        ffmpeg,
                        input,
                        output,
                        info,
                        encodingOptions)) {
                    while (true) {
                        control.checkCancelled();
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
                        if (frames % progressEveryFrames == 0L) {
                            progressListener.onProgress(progress(frames, start));
                        }
                    }
                }
            } finally {
                if (lifecycle != null) lifecycle.onStreamEnd();
            }

            progressListener.onProgress(progress(frames, start));
            return new VideoProcessResult(
                    output,
                    frames,
                    info,
                    Duration.between(start, Instant.now()),
                    sourceBackend + " -> " + processor.backendName());
        }
    }

    private static ProcessingProgress progress(long frames, Instant start) {
        Duration elapsed = Duration.between(start, Instant.now());
        double seconds = elapsed.toNanos() / 1_000_000_000d;
        double fps = seconds <= 0d ? 0d : frames / seconds;
        return new ProcessingProgress(frames, elapsed, fps);
    }
}
