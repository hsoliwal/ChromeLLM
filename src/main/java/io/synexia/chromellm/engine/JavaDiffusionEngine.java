package io.synexia.chromellm.engine;

import io.synexia.chromellm.api.DiffusionRequest;
import io.synexia.chromellm.api.DiffusionResult;
import java.util.Map;
import java.util.SplittableRandom;

public final class JavaDiffusionEngine implements DiffusionEngine {
    private final Denoiser denoiser;
    public JavaDiffusionEngine() { this(new ReferenceDenoiser()); }
    public JavaDiffusionEngine(Denoiser denoiser) { this.denoiser = denoiser; }

    @Override
    public DiffusionResult generate(DiffusionRequest request) {
        SplittableRandom rng = new SplittableRandom(request.seed());
        float[] latent = gaussianNoise(request.width() * request.height() * request.channels(), rng);
        DiffusionScheduler scheduler = new DiffusionScheduler(request.steps());
        for (int step = request.steps() - 1; step >= 0; step--) {
            float[] noise = denoiser.predictNoise(latent, request.width(), request.height(), request.channels(), step, request.steps(), request.condition(), request.guidanceScale());
            latent = scheduler.step(latent, noise, step, request.eta(), rng);
        }
        normalizeInPlace(latent);
        return new DiffusionResult(request.width(), request.height(), request.channels(), latent, request.seed(), Map.of("engine","java-reference-ddim","condition",request.condition().mode().name()));
    }

    private static float[] gaussianNoise(int size, SplittableRandom rng) {
        float[] values = new float[size];
        for (int i = 0; i < size; i += 2) {
            double u1 = Math.max(1e-12, rng.nextDouble()), u2 = rng.nextDouble();
            double mag = Math.sqrt(-2.0 * Math.log(u1));
            values[i] = (float)(mag * Math.cos(2.0 * Math.PI * u2));
            if (i + 1 < size) values[i + 1] = (float)(mag * Math.sin(2.0 * Math.PI * u2));
        }
        return values;
    }

    private static void normalizeInPlace(float[] values) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (float value : values) { min = Math.min(min, value); max = Math.max(max, value); }
        float range = Math.max(1e-8f, max - min);
        for (int i = 0; i < values.length; i++) values[i] = (values[i] - min) / range;
    }
}
