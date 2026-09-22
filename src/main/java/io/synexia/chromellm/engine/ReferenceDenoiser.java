package io.synexia.chromellm.engine;

import io.synexia.chromellm.api.DiffusionCondition;
import io.synexia.chromellm.api.DiffusionMode;

public final class ReferenceDenoiser implements Denoiser {
    @Override
    public float[] predictNoise(float[] latent, int width, int height, int channels, int step, int totalSteps, DiffusionCondition condition, float guidanceScale) {
        float[] prediction = new float[latent.length];
        float progress = totalSteps <= 1 ? 1f : 1f - ((float)step / (totalSteps - 1));
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) for (int c = 0; c < channels; c++) {
            int index = (y * width + x) * channels + c;
            float local = localAverage(latent, width, height, channels, x, y, c);
            float structure = latent[index] - local;
            float target = conditionTarget(condition, width, height, channels, x, y, c);
            float attraction = condition.mode() == DiffusionMode.UNCONDITIONED ? 0f : (latent[index] - target) * Math.min(guidanceScale / 10f, 2f);
            prediction[index] = structure * (0.55f + 0.35f * progress) + attraction * (0.15f + 0.35f * progress);
        }
        return prediction;
    }

    private static float localAverage(float[] data, int width, int height, int channels, int x, int y, int c) {
        float sum = 0f; int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = Math.max(0, Math.min(height - 1, y + dy));
            for (int dx = -1; dx <= 1; dx++) {
                int xx = Math.max(0, Math.min(width - 1, x + dx));
                sum += data[(yy * width + xx) * channels + c]; count++;
            }
        }
        return sum / count;
    }

    private static float conditionTarget(DiffusionCondition condition, int width, int height, int channels, int x, int y, int c) {
        float[] data = condition.data();
        return switch (condition.mode()) {
            case UNCONDITIONED -> 0f;
            case CLASS_LABEL -> hashColor(condition.classId(), c);
            case VECTOR -> data.length == 0 ? 0f : data[c % data.length];
            case DEPTH -> {
                int sx = Math.min(condition.width() - 1, x * condition.width() / Math.max(width, 1));
                int sy = Math.min(condition.height() - 1, y * condition.height() / Math.max(height, 1));
                float d = data[sy * condition.width() + sx];
                yield d * (0.65f + 0.35f * hashColor(c + 17, c));
            }
            case IMAGE -> {
                int sx = Math.min(condition.width() - 1, x * condition.width() / Math.max(width, 1));
                int sy = Math.min(condition.height() - 1, y * condition.height() / Math.max(height, 1));
                int sc = Math.min(condition.channels() - 1, c);
                yield data[(sy * condition.width() + sx) * condition.channels() + sc];
            }
        };
    }

    private static float hashColor(int value, int channel) {
        int h = value * 0x9E3779B9 + channel * 0x7F4A7C15;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65535f;
    }
}
