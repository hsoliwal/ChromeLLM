package io.synexia.chromellm.preset;

import io.synexia.chromellm.video.FrameTransform;
import io.synexia.chromellm.video.VideoEncodingOptions;

import java.util.Objects;

public record CompiledMediaPreset(
        FrameTransform transform,
        VideoEncodingOptions encodingOptions,
        int progressEveryFrames) {

    public CompiledMediaPreset {
        transform = Objects.requireNonNull(transform, "transform");
        encodingOptions = Objects.requireNonNull(encodingOptions, "encodingOptions");
        if (progressEveryFrames < 1) throw new IllegalArgumentException("progressEveryFrames must be positive");
    }
}
