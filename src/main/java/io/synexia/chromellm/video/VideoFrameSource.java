package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.io.IOException;

public interface VideoFrameSource extends AutoCloseable {
    VideoStreamInfo streamInfo();

    RgbaFrame nextFrame() throws IOException;

    String backendName();

    @Override
    void close() throws IOException;
}
