package io.synexia.chromellm.preset;

import java.util.List;

public record MediaPreset(
        Integer version,
        String pipeline,
        LutPreset lut,
        List<OverlayPreset> overlays,
        List<AutomationPreset> automation,
        EncodingPreset encoding,
        Integer progressEveryFrames) {
}
