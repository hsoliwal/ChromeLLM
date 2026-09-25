package io.synexia.chromellm.preset;

public record OverlayPreset(
        String file,
        Integer x,
        Integer y,
        Float opacity,
        Long startFrame,
        Long endFrame) {
}
