package io.synexia.chromellm.api;

import java.util.Arrays;
import java.util.Objects;

public record DiffusionCondition(DiffusionMode mode, float[] data, int width, int height, int channels, int classId) {
    public DiffusionCondition {
        Objects.requireNonNull(mode, "mode");
        data = data == null ? new float[0] : Arrays.copyOf(data, data.length);
        if (width < 0 || height < 0 || channels < 0) throw new IllegalArgumentException("dimensions must be non-negative");
        if ((mode == DiffusionMode.IMAGE || mode == DiffusionMode.DEPTH)
                && width * height * Math.max(channels, 1) != data.length) {
            throw new IllegalArgumentException("condition data does not match dimensions");
        }
    }
    @Override public float[] data() { return Arrays.copyOf(data, data.length); }
    public static DiffusionCondition none() { return new DiffusionCondition(DiffusionMode.UNCONDITIONED, new float[0], 0, 0, 0, -1); }
    public static DiffusionCondition image(float[] rgb, int width, int height) { return new DiffusionCondition(DiffusionMode.IMAGE, rgb, width, height, 3, -1); }
    public static DiffusionCondition depth(float[] depth, int width, int height) { return new DiffusionCondition(DiffusionMode.DEPTH, depth, width, height, 1, -1); }
    public static DiffusionCondition classLabel(int classId) { return new DiffusionCondition(DiffusionMode.CLASS_LABEL, new float[0], 0, 0, 0, classId); }
    public static DiffusionCondition vector(float[] vector) { return new DiffusionCondition(DiffusionMode.VECTOR, vector, 0, 0, 0, -1); }
}
