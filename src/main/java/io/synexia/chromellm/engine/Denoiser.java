package io.synexia.chromellm.engine;
import io.synexia.chromellm.api.DiffusionCondition;
@FunctionalInterface
public interface Denoiser {
    float[] predictNoise(float[] latent, int width, int height, int channels, int step, int totalSteps, DiffusionCondition condition, float guidanceScale);
}
