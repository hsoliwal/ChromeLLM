package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class ProcessFfmpegFrameSource implements VideoFrameSource {
    private final Process decoder;
    private final InputStream decoded;
    private final VideoStreamInfo info;
    private boolean eof;
    private boolean closed;

    public ProcessFfmpegFrameSource(
            Path input,
            String ffmpeg,
            FfmpegProbe probe) throws IOException, InterruptedException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(ffmpeg, "ffmpeg");
        Objects.requireNonNull(probe, "probe");
        this.info = probe.probe(input);
        this.decoder = new ProcessBuilder(List.of(
                ffmpeg,
                "-v", "error",
                "-i", input.toAbsolutePath().toString(),
                "-map", "0:v:0",
                "-f", "rawvideo",
                "-pix_fmt", "rgba",
                "pipe:1"))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        this.decoded = decoder.getInputStream();
    }

    @Override
    public VideoStreamInfo streamInfo() {
        return info;
    }

    @Override
    public RgbaFrame nextFrame() throws IOException {
        if (closed) throw new IllegalStateException("frame source is closed");
        if (eof) return null;

        byte[] frame = new byte[info.rgbaFrameBytes()];
        int read = readFully(decoded, frame);
        if (read == 0) {
            eof = true;
            return null;
        }
        if (read != frame.length) {
            throw new EOFException("truncated raw video frame: " + read + " of " + frame.length + " bytes");
        }
        return new RgbaFrame(info.width(), info.height(), frame);
    }

    @Override
    public String backendName() {
        return "ffmpeg-process";
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        decoded.close();
        try {
            int exit = decoder.waitFor();
            if (exit != 0 && !eof) {
                throw new IOException("ffmpeg decoder failed with exit code " + exit);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            decoder.destroyForcibly();
            throw new IOException("interrupted while closing ffmpeg decoder", exception);
        }
    }

    private static int readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(target, offset, target.length - offset);
            if (read < 0) break;
            offset += read;
        }
        return offset;
    }
}
