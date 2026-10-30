package io.synexia.chromellm.cli;

import io.synexia.chromellm.api.DiffusionCondition;
import io.synexia.chromellm.api.DiffusionRequest;
import io.synexia.chromellm.api.DiffusionResult;
import io.synexia.chromellm.engine.DiffusionEngine;
import io.synexia.chromellm.engine.JavaDiffusionEngine;
import io.synexia.chromellm.nativebridge.JnaDiffusionEngine;
import io.synexia.chromellm.nativebridge.JniDiffusionEngine;
import java.nio.file.Path;

/** Deterministic diffusion CLI; text inference has moved to standalone JNI. */
public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        Arguments o = Arguments.parse(args);
        DiffusionCondition condition = o.conditionVector == null
                ? DiffusionCondition.none() : DiffusionCondition.vector(parseVector(o.conditionVector));
        DiffusionRequest request = new DiffusionRequest(o.width, o.height, 3, o.steps, o.seed,
                o.guidance, o.eta, condition);
        try (DiffusionEngine engine = engine(o.engine)) {
            DiffusionResult result = engine.generate(request);
            PpmWriter.write(result, Path.of(o.output));
            System.out.printf("generated %s using %s seed=%d%n", o.output,
                    result.metadata().get("engine"), result.seed());
        }
    }

    private static float[] parseVector(String csv) {
        String[] parts = csv.split(",", -1);
        if (parts.length != 16) {
            throw new IllegalArgumentException("--condition-vector requires exactly 16 numbers");
        }
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            float value = Float.parseFloat(parts[i].trim());
            if (!Float.isFinite(value) || value < 0f || value > 1f) {
                throw new IllegalArgumentException("conditioning values must be finite and in [0,1]");
            }
            values[i] = value;
        }
        return values;
    }

    private static DiffusionEngine engine(String name) {
        return switch (name) {
            case "java" -> new JavaDiffusionEngine();
            case "jna" -> new JnaDiffusionEngine();
            case "jni" -> new JniDiffusionEngine();
            default -> throw new IllegalArgumentException("unknown engine: " + name);
        };
    }

    private static final class Arguments {
        String engine = "java", output = "diffusion.ppm", conditionVector;
        int width = 256, height = 256, steps = 40;
        long seed = 1L;
        float guidance = 7.5f, eta = 0f;

        static Arguments parse(String[] args) {
            Arguments result = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                String value = i + 1 < args.length ? args[i + 1] : null;
                switch (key) {
                    case "--engine" -> { result.engine = require(key, value); i++; }
                    case "--width" -> { result.width = Integer.parseInt(require(key, value)); i++; }
                    case "--height" -> { result.height = Integer.parseInt(require(key, value)); i++; }
                    case "--steps" -> { result.steps = Integer.parseInt(require(key, value)); i++; }
                    case "--seed" -> { result.seed = Long.parseLong(require(key, value)); i++; }
                    case "--guidance" -> { result.guidance = Float.parseFloat(require(key, value)); i++; }
                    case "--eta" -> { result.eta = Float.parseFloat(require(key, value)); i++; }
                    case "--output" -> { result.output = require(key, value); i++; }
                    case "--condition-vector" -> { result.conditionVector = require(key, value); i++; }
                    default -> throw new IllegalArgumentException("unknown argument: " + key);
                }
            }
            return result;
        }

        private static String require(String key, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing value for " + key);
            }
            return value;
        }
    }
}
