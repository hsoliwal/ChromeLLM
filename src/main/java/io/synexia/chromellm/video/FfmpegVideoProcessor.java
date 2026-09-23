package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.RgbaFrame;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
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

    public FfmpegVideoProcessor() {
        this(
                System.getProperty("chromellm.ffmpeg", "ffmpeg"),
                new FfmpegProbe());
    }

    public FfmpegVideoProcessor(String ffmpeg, FfmpegProbe probe) {
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.probe = Objects.requireNonNull(probe, "probe");
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

        VideoStreamInfo info = probe.probe(input);
        Instant start = Instant.now();

        Process decoder = new ProcessBuilder(decoderCommand(input))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        Process encoder = new ProcessBuilder(encoderCommand(input, output, info))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();

        long frames = 0L;
        Throwable failure = null;
        try (InputStream decoded = decoder.getInputStream();
             OutputStream encoded = encoder.getOutputStream()) {
            byte[] frameBuffer = new byte[info.rgbaFrameBytes()];
            while (true) {
                int read = readFrame(decoded, frameBuffer);
                if (read == 0) break;
                if (read != frameBuffer.length) {
                    throw new EOFException("truncated raw video frame: " + read + " of " + frameBuffer.length + " bytes");
                }

                RgbaFrame source = new RgbaFrame(info.width(), info.height(), frameBuffer);
                RgbaFrame result = transform.apply(source, frames);
                if (result.width() != info.width() || result.height() != info.height()) {
                    throw new IllegalStateException("frame transform changed dimensions");
                }
                encoded.write(result.pixels());
                frames++;
            }
        } catch (Throwable throwable) {
            failure = throwable;
            throw throwable;
        } finally {
            if (failure != null) {
                decoder.destroyForcibly();
                encoder.destroyForcibly();
            }
        }

        int decoderExit = decoder.waitFor();
        int encoderExit = encoder.waitFor();
        if (decoderExit != 0) throw new IOException("ffmpeg decoder failed with exit code " + decoderExit);
        if (encoderExit != 0) throw new IOException("ffmpeg encoder failed with exit code " + encoderExit);

        return new VideoProcessResult(
                output,
                frames,
                info,
                Duration.between(start, Instant.now()),
                processor.backendName());
    }

    private List<String> decoderCommand(Path input) {
        return List.of(
                ffmpeg,
                "-v", "error",
                "-i", input.toAbsolutePath().toString(),
                "-map", "0:v:0",
                "-f", "rawvideo",
                "-pix_fmt", "rgba",
                "pipe:1");
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

    private static int readFrame(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) break;
            offset += read;
        }
        return offset;
    }
}
