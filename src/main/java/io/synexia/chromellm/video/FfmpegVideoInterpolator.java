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
        return interpolate(
                input,
                output,
                vision,
                factor,
                VideoEncodingOptions.defaults(),
                ProcessingProgressListener.NONE,
                ProcessingControl.NEVER_CANCELLED,
                30);
    }

    public VideoProcessResult interpolate(
            Path input,
            Path output,
            VisionProcessor vision,
            int factor,
            VideoEncodingOptions encodingOptions,
            ProcessingProgressListener progressListener,
            ProcessingControl control,
            int progressEveryFrames) throws IOException, InterruptedException {
        if (factor < 2 || factor > 8) {
            throw new IllegalArgumentException("interpolation factor must be between 2 and 8");
        }
        Objects.requireNonNull(vision, "vision");
        Objects.requireNonNull(encodingOptions, "encodingOptions");
        progressListener = Objects.requireNonNullElse(progressListener, ProcessingProgressListener.NONE);
        control = Objects.requireNonNullElse(control, ProcessingControl.NEVER_CANCELLED);
        if (progressEveryFrames < 1) throw new IllegalArgumentException("progressEveryFrames must be positive");

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
                    outputInfo,
                    encodingOptions)) {
                control.checkCancelled();
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
                notifyProgress(written, start, progressEveryFrames, progressListener);

                while (true) {
                    control.checkCancelled();
                    RgbaFrame next = source.nextFrame();
                    if (next == null) break;

                    for (int step = 1; step < factor; step++) {
                        control.checkCancelled();
                        float position = (float)step / factor;
                        encoder.write(vision.interpolate(previous, next, position));
                        written++;
                        notifyProgress(written, start, progressEveryFrames, progressListener);
                    }

                    encoder.write(next);
                    written++;
                    notifyProgress(written, start, progressEveryFrames, progressListener);
                    previous = next;
                }
            }

            progressListener.onProgress(progress(written, start));
            return new VideoProcessResult(
                    output,
                    written,
                    outputInfo,
                    Duration.between(start, Instant.now()),
                    source.backendName() + " -> " + vision.backendName());
        }
    }

    private static void notifyProgress(
            long frames,
            Instant start,
            int progressEveryFrames,
            ProcessingProgressListener listener) {
        if (frames % progressEveryFrames == 0L) {
            listener.onProgress(progress(frames, start));
        }
    }

    private static ProcessingProgress progress(long frames, Instant start) {
        Duration elapsed = Duration.between(start, Instant.now());
        double seconds = elapsed.toNanos() / 1_000_000_000d;
        double fps = seconds <= 0d ? 0d : frames / seconds;
        return new ProcessingProgress(frames, elapsed, fps);
    }
}
