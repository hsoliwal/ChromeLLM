package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.gpu.PixelParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class TransformSpecParser {
    private final ImageProcessor processor;

    public TransformSpecParser(ImageProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    public TransformChain parsePipeline(String specification) {
        if (specification == null || specification.isBlank()) {
            throw new IllegalArgumentException("pipeline specification is empty");
        }
        List<FrameTransform> transforms = new ArrayList<>();
        for (String raw : specification.split(",")) {
            String token = raw.trim();
            if (!token.isEmpty()) transforms.add(parseTransform(token));
        }
        if (transforms.isEmpty()) throw new IllegalArgumentException("pipeline specification is empty");
        return new TransformChain(transforms);
    }

    public FrameTransform parseTransform(String specification) {
        String[] parts = specification.split(":");
        if (parts.length == 0 || parts[0].isBlank()) throw new IllegalArgumentException("empty transform");
        String name = normalize(parts[0]);

        return switch (name) {
            case "temporal_denoise" -> temporalDenoise(parts);
            case "stabilize", "stabilization" -> stabilization(parts);
            case "unsharp", "unsharp_mask" -> unsharp(parts);
            case "region" -> region(parts);
            case "range" -> range(parts);
            default -> pixel(parts, 0);
        };
    }

    private FrameTransform temporalDenoise(String[] parts) {
        float currentWeight = floatAt(parts, 1, 0.25f);
        double cutThreshold = doubleAt(parts, 2, 0.35d);
        int sampleStride = intAt(parts, 3, 4);
        return new TemporalDenoiseTransform(
                processor,
                currentWeight,
                new SceneCutDetector(cutThreshold, sampleStride));
    }

    private FrameTransform stabilization(String[] parts) {
        int radius = intAt(parts, 1, 8);
        int stride = intAt(parts, 2, 4);
        double smoothing = doubleAt(parts, 3, 0.75d);
        double cutThreshold = doubleAt(parts, 4, 0.35d);
        return new StabilizationTransform(
                new BlockMatchingTranslationEstimator(radius, stride),
                new SceneCutDetector(cutThreshold, stride),
                smoothing);
    }

    private FrameTransform unsharp(String[] parts) {
        int radius = intAt(parts, 1, 2);
        float amount = floatAt(parts, 2, 1f);
        int threshold = intAt(parts, 3, 3);
        return new UnsharpMaskTransform(processor, radius, amount, threshold);
    }

    private FrameTransform region(String[] parts) {
        if (parts.length < 6) {
            throw new IllegalArgumentException("region syntax: region:x:y:width:height:operation[:p0:p1:p2:p3]");
        }
        FrameRegion region = new FrameRegion(
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]));
        return new RegionTransform(region, pixel(parts, 5));
    }

    private FrameTransform range(String[] parts) {
        if (parts.length < 4) {
            throw new IllegalArgumentException("range syntax: range:startInclusive:endExclusive:operation[:p0:p1:p2:p3]");
        }
        long start = Long.parseLong(parts[1]);
        long end = Long.parseLong(parts[2]);
        return new FrameRangeTransform(start, end, pixel(parts, 3));
    }

    private FrameTransform pixel(String[] parts, int operationIndex) {
        PixelOperation operation = enumValue(PixelOperation.class, parts[operationIndex]);
        int parameterStart = operationIndex + 1;
        PixelParameters parameters = new PixelParameters(
                floatAt(parts, parameterStart, 0f),
                floatAt(parts, parameterStart + 1, 0f),
                floatAt(parts, parameterStart + 2, 0f),
                floatAt(parts, parameterStart + 3, 0f));
        return new GpuFrameTransform(processor, operation, parameters);
    }

    private static int intAt(String[] values, int index, int fallback) {
        return index < values.length && !values[index].isBlank()
                ? Integer.parseInt(values[index])
                : fallback;
    }

    private static float floatAt(String[] values, int index, float fallback) {
        return index < values.length && !values[index].isBlank()
                ? Float.parseFloat(values[index])
                : fallback;
    }

    private static double doubleAt(String[] values, int index, double fallback) {
        return index < values.length && !values[index].isBlank()
                ? Double.parseDouble(values[index])
                : fallback;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, normalize(value).toUpperCase(Locale.ROOT));
    }
}
