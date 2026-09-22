package io.synexia.chromellm.engine;

public final class DiffusionScheduler {
    private final float[] alphaBar;

    public DiffusionScheduler(int steps) {
        if (steps < 1) throw new IllegalArgumentException("steps must be positive");
        this.alphaBar = new float[steps];
        float product = 1f;
        for (int i = 0; i < steps; i++) {
            float t = steps == 1 ? 0f : (float)i / (steps - 1);
            float beta = 0.0001f + t * (0.02f - 0.0001f);
            product *= (1f - beta);
            alphaBar[i] = product;
        }
    }

    public float[] step(float[] x, float[] predictedNoise, int index, float eta, java.util.random.RandomGenerator rng) {
        if (x.length != predictedNoise.length) throw new IllegalArgumentException("latent and noise prediction size differ");
        float aBar = alphaBar[index];
        float prevABar = index == 0 ? 1f : alphaBar[index - 1];
        float sqrtABar = (float)Math.sqrt(aBar);
        float sqrtOneMinusABar = (float)Math.sqrt(Math.max(1e-12f, 1f - aBar));
        float sigma = eta * (float)Math.sqrt(Math.max(0f, ((1f - prevABar) / (1f - aBar)) * (1f - aBar / prevABar)));
        float dir = (float)Math.sqrt(Math.max(0f, 1f - prevABar - sigma * sigma));
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            float x0 = (x[i] - sqrtOneMinusABar * predictedNoise[i]) / Math.max(sqrtABar, 1e-6f);
            float z = index == 0 || sigma == 0f ? 0f : gaussian(rng);
            out[i] = (float)Math.sqrt(prevABar) * x0 + dir * predictedNoise[i] + sigma * z;
        }
        return out;
    }

    private static float gaussian(java.util.random.RandomGenerator rng) {
        double u1 = Math.max(1e-12, rng.nextDouble());
        double u2 = rng.nextDouble();
        return (float)(Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2));
    }
}
