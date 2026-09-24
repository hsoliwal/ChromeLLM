package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class GeometryTransform implements FrameTransform {
    private final GeometryOperation operation;

    public GeometryTransform(GeometryOperation operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        byte[] source = frame.pixels();
        byte[] output = new byte[source.length];
        int width = frame.width();
        int height = frame.height();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sx;
                int sy;
                switch (operation) {
                    case FLIP_HORIZONTAL -> {
                        sx = width - 1 - x;
                        sy = y;
                    }
                    case FLIP_VERTICAL -> {
                        sx = x;
                        sy = height - 1 - y;
                    }
                    case ROTATE_180 -> {
                        sx = width - 1 - x;
                        sy = height - 1 - y;
                    }
                    default -> throw new IllegalStateException("unsupported geometry operation: " + operation);
                }
                int src = (sy * width + sx) * 4;
                int dst = (y * width + x) * 4;
                System.arraycopy(source, src, output, dst, 4);
            }
        }
        return new RgbaFrame(width, height, output);
    }
}
