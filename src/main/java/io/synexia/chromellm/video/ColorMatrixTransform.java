package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Arrays;

public final class ColorMatrixTransform implements FrameTransform {
    private static final int MATRIX_SIZE = 20;
    private final float[] matrix;

    public ColorMatrixTransform(float[] matrix) {
        if (matrix == null || matrix.length != MATRIX_SIZE) {
            throw new IllegalArgumentException("color matrix must contain exactly 20 values");
        }
        this.matrix = Arrays.copyOf(matrix, matrix.length);
        for (float value : this.matrix) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("color matrix values must be finite");
        }
    }

    public static ColorMatrixTransform identity() {
        return new ColorMatrixTransform(new float[]{
                1,0,0,0,0,
                0,1,0,0,0,
                0,0,1,0,0,
                0,0,0,1,0
        });
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        byte[] source = frame.pixels();
        byte[] output = new byte[source.length];
        for (int i = 0; i < source.length; i += 4) {
            float r = source[i] & 255;
            float g = source[i + 1] & 255;
            float b = source[i + 2] & 255;
            float a = source[i + 3] & 255;
            output[i] = toByte(applyRow(0, r, g, b, a));
            output[i + 1] = toByte(applyRow(5, r, g, b, a));
            output[i + 2] = toByte(applyRow(10, r, g, b, a));
            output[i + 3] = toByte(applyRow(15, r, g, b, a));
        }
        return new RgbaFrame(frame.width(), frame.height(), output);
    }

    private float applyRow(int offset, float r, float g, float b, float a) {
        return matrix[offset] * r
                + matrix[offset + 1] * g
                + matrix[offset + 2] * b
                + matrix[offset + 3] * a
                + matrix[offset + 4] * 255f;
    }

    private static byte toByte(float value) {
        return (byte)Math.round(Math.max(0f, Math.min(255f, value)));
    }
}
