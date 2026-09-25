package io.synexia.chromellm.preset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.synexia.chromellm.gpu.ImageIoFrames;
import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.video.AudioMode;
import io.synexia.chromellm.video.CubeLutParser;
import io.synexia.chromellm.video.Easing;
import io.synexia.chromellm.video.FrameRangeTransform;
import io.synexia.chromellm.video.FrameTransform;
import io.synexia.chromellm.video.Keyframe;
import io.synexia.chromellm.video.KeyframeCurve;
import io.synexia.chromellm.video.KeyframedPixelTransform;
import io.synexia.chromellm.video.Lut3dTransform;
import io.synexia.chromellm.video.OverlayImageTransform;
import io.synexia.chromellm.video.TransformChain;
import io.synexia.chromellm.video.TransformSpecParser;
import io.synexia.chromellm.video.VideoCodec;
import io.synexia.chromellm.video.VideoEncodingOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class MediaPresetLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public CompiledMediaPreset load(Path presetPath, ImageProcessor processor) throws IOException {
        Objects.requireNonNull(presetPath, "presetPath");
        Objects.requireNonNull(processor, "processor");

        MediaPreset preset = mapper.readValue(presetPath.toFile(), MediaPreset.class);
        if (preset.version() == null || preset.version() != 1) {
            throw new IllegalArgumentException("unsupported media preset version: " + preset.version());
        }
        Path base = presetPath.toAbsolutePath().normalize().getParent();
        List<FrameTransform> transforms = new ArrayList<>();

        if (preset.pipeline() != null && !preset.pipeline().isBlank()) {
            transforms.addAll(new TransformSpecParser(processor)
                    .parsePipeline(preset.pipeline())
                    .transforms());
        }

        if (preset.lut() != null && preset.lut().file() != null && !preset.lut().file().isBlank()) {
            Path lutPath = resolve(base, preset.lut().file());
            float intensity = preset.lut().intensity() == null ? 1f : preset.lut().intensity();
            transforms.add(new Lut3dTransform(new CubeLutParser().parse(lutPath), intensity));
        }

        for (OverlayPreset overlay : safe(preset.overlays())) {
            if (overlay.file() == null || overlay.file().isBlank()) {
                throw new IllegalArgumentException("overlay file is required");
            }
            FrameTransform transform = new OverlayImageTransform(
                    processor,
                    ImageIoFrames.read(resolve(base, overlay.file())),
                    overlay.x() == null ? 0 : overlay.x(),
                    overlay.y() == null ? 0 : overlay.y(),
                    overlay.opacity() == null ? 1f : overlay.opacity());
            long start = overlay.startFrame() == null ? 0L : overlay.startFrame();
            long end = overlay.endFrame() == null ? Long.MAX_VALUE : overlay.endFrame();
            if (start != 0L || end != Long.MAX_VALUE) {
                transform = new FrameRangeTransform(start, end, transform);
            }
            transforms.add(transform);
        }

        for (AutomationPreset automation : safe(preset.automation())) {
            transforms.add(compileAutomation(processor, automation));
        }

        VideoEncodingOptions encoding = compileEncoding(preset.encoding());
        int progressEveryFrames = preset.progressEveryFrames() == null ? 30 : preset.progressEveryFrames();
        return new CompiledMediaPreset(
                new TransformChain(transforms),
                encoding,
                progressEveryFrames);
    }

    private static FrameTransform compileAutomation(ImageProcessor processor, AutomationPreset preset) {
        if (preset.operation() == null || preset.operation().isBlank()) {
            throw new IllegalArgumentException("automation operation is required");
        }
        PixelOperation operation = PixelOperation.valueOf(normalize(preset.operation()));
        return new KeyframedPixelTransform(
                processor,
                operation,
                curve(preset.p0()),
                curve(preset.p1()),
                curve(preset.p2()),
                curve(preset.p3()));
    }

    private static KeyframeCurve curve(List<KeyframePreset> presets) {
        if (presets == null || presets.isEmpty()) return KeyframeCurve.constant(0f);
        return new KeyframeCurve(presets.stream().map(preset -> {
            if (preset.frame() == null || preset.value() == null) {
                throw new IllegalArgumentException("keyframe frame and value are required");
            }
            Easing easing = preset.easing() == null || preset.easing().isBlank()
                    ? Easing.LINEAR
                    : Easing.valueOf(normalize(preset.easing()));
            return new Keyframe(preset.frame(), preset.value(), easing);
        }).toList());
    }

    private static VideoEncodingOptions compileEncoding(EncodingPreset preset) {
        VideoEncodingOptions defaults = VideoEncodingOptions.defaults();
        if (preset == null) return defaults;
        VideoCodec codec = preset.videoCodec() == null
                ? defaults.videoCodec()
                : VideoCodec.valueOf(normalize(preset.videoCodec()));
        AudioMode audio = preset.audioMode() == null
                ? defaults.audioMode()
                : AudioMode.valueOf(normalize(preset.audioMode()));
        return new VideoEncodingOptions(
                codec,
                preset.preset() == null ? defaults.preset() : preset.preset(),
                preset.crf() == null ? defaults.crf() : preset.crf(),
                preset.pixelFormat() == null ? defaults.pixelFormat() : preset.pixelFormat(),
                audio,
                preset.audioBitrateKbps() == null ? defaults.audioBitrateKbps() : preset.audioBitrateKbps(),
                preset.threads() == null ? defaults.threads() : preset.threads(),
                preset.extraArguments() == null ? defaults.extraArguments() : preset.extraArguments());
    }

    private static Path resolve(Path base, String child) {
        Path path = Path.of(child);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
