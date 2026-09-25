package io.synexia.chromellm.preset;

import java.util.List;

public record AutomationPreset(
        String operation,
        List<KeyframePreset> p0,
        List<KeyframePreset> p1,
        List<KeyframePreset> p2,
        List<KeyframePreset> p3) {
}
