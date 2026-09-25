package io.synexia.chromellm.video;

import java.time.Duration;

public record ProcessingProgress(
        long framesProcessed,
        Duration elapsed,
        double processingFramesPerSecond) {

    public ProcessingProgress {
        if (framesProcessed < 0L) throw new IllegalArgumentException("framesProcessed must be non-negative");
        if (elapsed == null || elapsed.isNegative()) throw new IllegalArgumentException("elapsed must be non-negative");
        if (!Double.isFinite(processingFramesPerSecond) || processingFramesPerSecond < 0d) {
            throw new IllegalArgumentException("processingFramesPerSecond must be finite and non-negative");
        }
    }
}
