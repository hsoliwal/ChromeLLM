package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class Lut3dTransform implements FrameTransform {
    private final CubeLut lut;
    private final float intensity;

    public Lut3dTransform(CubeLut lut, float intensity) {
        this.lut = Objects.requireNonNull(lut, "lut");
        if (!Float.isFinite(intensity) || intensity < 0f || intensity > 1f) {
            throw new IllegalArgumentException("intensity must be between 0 and 1");
        }
        this.intensity = intensity;
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        byte[] source = frame.pixels();
        byte[] output = source.clone();
        for (int i = 0; i < source.length; i += 4) {
            float r = (source[i] & 255) / 255f;
            float g = (source[i + 1] & 255) / 255f;
            float b = (source[i + 2] & 255) / 255f;
            float[] mapped = lut.sample(r, g, b);
            output[i] = toByte(lerp(r, mapped[0], intensity) * 255f);
            output[i + 1] = toByte(lerp(g, mapped[1], intensity) * 255f);
            output[i + 2] = toByte(lerp(b, mapped[2], intensity) * 255f);
        }
        return new RgbaFrame(frame.width(), frame.height(), output);
    }

    private static float lerp(float left, float right, float amount) {
        return left + (right - left) * amount;
    }

    private static byte toByte(float value) {
        return (byte)Math.round(Math.max(0f, Math.min(255f, value)));
    }
}
