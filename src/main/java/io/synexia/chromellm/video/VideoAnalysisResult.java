package io.synexia.chromellm.video;

import java.time.Duration;
import java.util.List;

public record VideoAnalysisResult(
        long framesAnalyzed,
        List<SceneCut> sceneCuts,
        double averageLuma,
        double minimumLuma,
        double maximumLuma,
        VideoStreamInfo stream,
        Duration elapsed,
        String decoderBackend) {
    public VideoAnalysisResult {
        sceneCuts = List.copyOf(sceneCuts);
    }
}
