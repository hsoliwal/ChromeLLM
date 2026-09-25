package io.synexia.chromellm.video;

import java.util.Arrays;

public final class CubeLut {
    private final int size;
    private final float[] domainMin;
    private final float[] domainMax;
    private final float[] table;

    public CubeLut(int size, float[] domainMin, float[] domainMax, float[] table) {
        if (size < 2 || size > 256) throw new IllegalArgumentException("LUT size must be between 2 and 256");
        if (domainMin == null || domainMin.length != 3 || domainMax == null || domainMax.length != 3) {
            throw new IllegalArgumentException("LUT domain vectors must contain 3 values");
        }
        int expected = Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(size, size), size), 3);
        if (table == null || table.length != expected) {
            throw new IllegalArgumentException("LUT table length does not match size");
        }
        this.size = size;
        this.domainMin = Arrays.copyOf(domainMin, 3);
        this.domainMax = Arrays.copyOf(domainMax, 3);
        this.table = Arrays.copyOf(table, table.length);
        for (int channel = 0; channel < 3; channel++) {
            if (!(this.domainMax[channel] > this.domainMin[channel])) {
                throw new IllegalArgumentException("LUT domain max must exceed min");
            }
        }
    }

    public int size() {
        return size;
    }

    public float[] domainMin() {
        return Arrays.copyOf(domainMin, domainMin.length);
    }

    public float[] domainMax() {
        return Arrays.copyOf(domainMax, domainMax.length);
    }

    public float[] sample(float red, float green, float blue) {
        float x = coordinate(red, 0);
        float y = coordinate(green, 1);
        float z = coordinate(blue, 2);

        int x0 = (int)Math.floor(x);
        int y0 = (int)Math.floor(y);
        int z0 = (int)Math.floor(z);
        int x1 = Math.min(size - 1, x0 + 1);
        int y1 = Math.min(size - 1, y0 + 1);
        int z1 = Math.min(size - 1, z0 + 1);

        float tx = x - x0;
        float ty = y - y0;
        float tz = z - z0;
        float[] output = new float[3];

        for (int channel = 0; channel < 3; channel++) {
            float c000 = value(x0, y0, z0, channel);
            float c100 = value(x1, y0, z0, channel);
            float c010 = value(x0, y1, z0, channel);
            float c110 = value(x1, y1, z0, channel);
            float c001 = value(x0, y0, z1, channel);
            float c101 = value(x1, y0, z1, channel);
            float c011 = value(x0, y1, z1, channel);
            float c111 = value(x1, y1, z1, channel);

            float c00 = lerp(c000, c100, tx);
            float c10 = lerp(c010, c110, tx);
            float c01 = lerp(c001, c101, tx);
            float c11 = lerp(c011, c111, tx);
            float c0 = lerp(c00, c10, ty);
            float c1 = lerp(c01, c11, ty);
            output[channel] = lerp(c0, c1, tz);
        }
        return output;
    }

    private float coordinate(float value, int channel) {
        float normalized = (value - domainMin[channel]) / (domainMax[channel] - domainMin[channel]);
        return Math.max(0f, Math.min(1f, normalized)) * (size - 1);
    }

    private float value(int red, int green, int blue, int channel) {
        int row = red + size * green + size * size * blue;
        return table[row * 3 + channel];
    }

    private static float lerp(float left, float right, float amount) {
        return left + (right - left) * amount;
    }
}
