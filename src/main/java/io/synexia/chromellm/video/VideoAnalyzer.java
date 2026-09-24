package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VideoAnalyzer {
    private final String ffmpeg;
    private final FfmpegProbe probe;
    private final DecoderBackend decoderBackend;

    public VideoAnalyzer(String ffmpeg, FfmpegProbe probe, DecoderBackend decoderBackend) {
        this.ffmpeg = Objects.requireNonNull(ffmpeg, "ffmpeg");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.decoderBackend = Objects.requireNonNull(decoderBackend, "decoderBackend");
    }

    public VideoAnalysisResult analyze(
            Path input,
            SceneCutDetector detector,
            int frameStride) throws IOException, InterruptedException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(detector, "detector");
        if (frameStride < 1) throw new IllegalArgumentException("frameStride must be positive");

        Instant start = Instant.now();
        try (VideoFrameSource source = VideoFrameSources.open(input, decoderBackend, ffmpeg, probe)) {
            VideoStreamInfo info = source.streamInfo();
            List<SceneCut> cuts = new ArrayList<>();
            RgbaFrame previousSample = null;
            long frameIndex = 0L;
            long sampled = 0L;
            double sum = 0d;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;

            while (true) {
                RgbaFrame frame = source.nextFrame();
                if (frame == null) break;
                if (frameIndex % frameStride == 0L) {
                    double luma = FrameStatistics.averageLuma(frame);
                    sum += luma;
                    min = Math.min(min, luma);
                    max = Math.max(max, luma);
                    sampled++;
                    if (previousSample != null) {
                        double score = detector.score(previousSample, frame);
                        if (score >= detector.threshold()) cuts.add(new SceneCut(frameIndex, score));
                    }
                    previousSample = frame;
                }
                frameIndex++;
            }

            double average = sampled == 0L ? 0d : sum / sampled;
            if (sampled == 0L) {
                min = 0d;
                max = 0d;
            }
            return new VideoAnalysisResult(
                    frameIndex,
                    cuts,
                    average,
                    min,
                    max,
                    info,
                    Duration.between(start, Instant.now()),
                    source.backendName());
        }
    }
}
