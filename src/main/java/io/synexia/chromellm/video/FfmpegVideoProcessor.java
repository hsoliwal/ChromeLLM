package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

            Process encoder = new ProcessBuilder(encoderCommand(input, output, info))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();

            boolean completed = false;
            try (OutputStream encoded = encoder.getOutputStream()) {
                while (true) {
                    RgbaFrame frame = source.nextFrame();
                    if (frame == null) break;

                    RgbaFrame result = Objects.requireNonNull(
                            transform.apply(frame, frames),
                            "frame transform returned null");
                    if (result.width() != info.width() || result.height() != info.height()) {
                        throw new IllegalStateException("frame transform changed dimensions");
                    }

                    encoded.write(result.pixels());
                    frames++;
                }
                completed = true;
            } finally {
                if (!completed) {
                    encoder.destroyForcibly();
                }
            }

            int encoderExit = encoder.waitFor();
            if (encoderExit != 0) {
                throw new IOException("ffmpeg encoder failed with exit code " + encoderExit);
            }

            return new VideoProcessResult(
                    output,
                    frames,
                    info,
                    Duration.between(start, Instant.now()),
                    sourceBackend + " -> " + processor.backendName());
        }
    }

    private List<String> encoderCommand(Path input, Path output, VideoStreamInfo info) {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-v"); command.add("error");
        command.add("-y");
        command.add("-f"); command.add("rawvideo");
        command.add("-pix_fmt"); command.add("rgba");
        command.add("-s"); command.add(info.width() + "x" + info.height());
        command.add("-r"); command.add(String.format(Locale.ROOT, "%.8f", info.framesPerSecond()));
        command.add("-i"); command.add("pipe:0");
        command.add("-i"); command.add(input.toAbsolutePath().toString());
        command.add("-map"); command.add("0:v:0");
        command.add("-map"); command.add("1:a?");
        command.add("-c:v"); command.add("libx264");
        command.add("-preset"); command.add("veryfast");
        command.add("-crf"); command.add("18");
        command.add("-pix_fmt"); command.add("yuv420p");
        command.add("-c:a"); command.add("copy");
        command.add("-shortest");
        command.add(output.toAbsolutePath().toString());
        return List.copyOf(command);
    }
}
