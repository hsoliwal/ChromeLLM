package io.synexia.chromellm.video.nativebridge;

import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.video.VideoFrameSource;
import io.synexia.chromellm.video.VideoStreamInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class JniFfmpegFrameSource implements VideoFrameSource {
    private long handle;
    private final VideoStreamInfo info;
    private final String version;

    public JniFfmpegFrameSource(Path input) {
        Objects.requireNonNull(input, "input");
        this.handle = NativeFfmpegDecoder.open(input.toAbsolutePath().toString());
        if (handle == 0L) throw new IllegalStateException("native FFmpeg decoder failed to open input");
        this.info = new VideoStreamInfo(
                NativeFfmpegDecoder.width(handle),
                NativeFfmpegDecoder.height(handle),
                NativeFfmpegDecoder.framesPerSecond(handle));
        this.version = NativeFfmpegDecoder.version();
    }

    @Override
    public VideoStreamInfo streamInfo() {
        return info;
    }

    @Override
    public RgbaFrame nextFrame() throws IOException {
        ensureOpen();
        byte[] rgba = new byte[info.rgbaFrameBytes()];
        int rc = NativeFfmpegDecoder.nextRgba(handle, rgba);
        if (rc == 0) return null;
        if (rc < 0) throw new IOException("native FFmpeg decode failed with code " + rc);
        return new RgbaFrame(info.width(), info.height(), rgba);
    }

    public void seekMillis(long millis) throws IOException {
        ensureOpen();
        int rc = NativeFfmpegDecoder.seekMillis(handle, millis);
        if (rc < 0) throw new IOException("native FFmpeg seek failed with code " + rc);
    }

    @Override
    public String backendName() {
        return "ffmpeg-jni:" + version;
    }

    @Override
    public void close() {
        if (handle != 0L) {
            NativeFfmpegDecoder.close(handle);
            handle = 0L;
        }
    }

    private void ensureOpen() {
        if (handle == 0L) throw new IllegalStateException("frame source is closed");
    }
}
