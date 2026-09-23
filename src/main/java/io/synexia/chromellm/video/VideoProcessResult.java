package io.synexia.chromellm.video;

import java.nio.file.Path;
import java.time.Duration;

public record VideoProcessResult(
        Path output,
        long framesProcessed,
        VideoStreamInfo stream,
        Duration elapsed,
        String processorBackend) {
}
