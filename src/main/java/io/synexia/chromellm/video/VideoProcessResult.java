package io.synexia.chromellm.video;

import java.nio.file.Path;
import java.time.Duration;

public record VideoProcessResult(
        Path output,
        long framesProcessed,
        VideoStreamInfo stream,
        Duration elapsed,
        String processorBackend) {

    public double processingFramesPerSecond() {
        double seconds = elapsed.toNanos() / 1_000_000_000d;
        return seconds <= 0d ? 0d : framesProcessed / seconds;
    }

    public double realtimeFactor() {
        return stream.framesPerSecond() <= 0d
                ? 0d
                : processingFramesPerSecond() / stream.framesPerSecond();
    }
}
