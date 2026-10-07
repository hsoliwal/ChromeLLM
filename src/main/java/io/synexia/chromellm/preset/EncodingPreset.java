package io.synexia.chromellm.preset;

import java.util.List;

public record EncodingPreset(
        String videoCodec,
        String preset,
        Integer crf,
        String pixelFormat,
        String audioMode,
        Integer audioBitrateKbps,
        Integer threads,
        List<String> extraArguments) {
}
