package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class FfmpegRgbaEncoder implements AutoCloseable {
    private final Process process;
    private final OutputStream output;
    private final VideoStreamInfo info;
    private boolean closed;

    FfmpegRgbaEncoder(
            String ffmpeg,
            Path originalInput,
            Path outputPath,
            VideoStreamInfo info) throws IOException {
        this(ffmpeg, originalInput, outputPath, info, VideoEncodingOptions.defaults());
    }

    FfmpegRgbaEncoder(
            String ffmpeg,
            Path originalInput,
            Path outputPath,
            VideoStreamInfo info,
            VideoEncodingOptions encodingOptions) throws IOException {
        this.info = Objects.requireNonNull(info, "info");
        this.process = new ProcessBuilder(command(
                ffmpeg,
                originalInput,
                outputPath,
                info,
                Objects.requireNonNull(encodingOptions, "encodingOptions")))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        this.output = process.getOutputStream();
    }

    void write(RgbaFrame frame) throws IOException {
        if (closed) throw new IllegalStateException("encoder is closed");
        if (frame.width() != info.width() || frame.height() != info.height()) {
            throw new IllegalArgumentException("frame dimensions differ from encoder stream");
        }
        output.write(frame.pixels());
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        IOException failure = null;
        try {
            output.close();
        } catch (IOException exception) {
            failure = exception;
        }

        try {
            int exit = process.waitFor();
            if (exit != 0) {
                IOException exitFailure = new IOException("ffmpeg encoder failed with exit code " + exit);
                if (failure == null) failure = exitFailure;
                else failure.addSuppressed(exitFailure);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            IOException interrupted = new IOException("interrupted while closing ffmpeg encoder", exception);
            if (failure == null) failure = interrupted;
            else failure.addSuppressed(interrupted);
        }

        if (failure != null) throw failure;
    }

    static List<String> command(
            String ffmpeg,
            Path originalInput,
            Path outputPath,
            VideoStreamInfo info,
            VideoEncodingOptions options) {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-v"); command.add("error");
        command.add("-y");
        command.add("-f"); command.add("rawvideo");
        command.add("-pix_fmt"); command.add("rgba");
        command.add("-s"); command.add(info.width() + "x" + info.height());
        command.add("-r"); command.add(String.format(Locale.ROOT, "%.8f", info.framesPerSecond()));
        command.add("-i"); command.add("pipe:0");
        command.add("-i"); command.add(originalInput.toAbsolutePath().toString());
        command.add("-map"); command.add("0:v:0");
        if (options.audioMode() != AudioMode.NONE) {
            command.add("-map"); command.add("1:a?");
        }

        command.add("-c:v"); command.add(options.videoCodec().ffmpegName());
        addVideoQualityArguments(command, options);
        command.add("-pix_fmt"); command.add(options.pixelFormat());
        if (options.threads() > 0) {
            command.add("-threads"); command.add(Integer.toString(options.threads()));
        }

        switch (options.audioMode()) {
            case COPY -> {
                command.add("-c:a");
                command.add("copy");
            }
            case AAC -> {
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add(options.audioBitrateKbps() + "k");
            }
            case NONE -> command.add("-an");
        }

        command.add("-shortest");
        command.addAll(options.extraArguments());
        command.add(outputPath.toAbsolutePath().toString());
        return List.copyOf(command);
    }

    private static void addVideoQualityArguments(List<String> command, VideoEncodingOptions options) {
        switch (options.videoCodec()) {
            case H264, H265 -> {
                command.add("-preset");
                command.add(options.preset());
                command.add("-crf");
                command.add(Integer.toString(options.crf()));
            }
            case AV1, VP9 -> {
                command.add("-crf");
                command.add(Integer.toString(options.crf()));
                command.add("-b:v");
                command.add("0");
            }
        }
    }
}
