package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.Objects;

public final class GeometryTransform implements FrameTransform, FrameTransformShape {
    private final GeometryOperation operation;

    public GeometryTransform(GeometryOperation operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        return switch (operation) {
            case FLIP_HORIZONTAL -> flipHorizontal(frame);
            case FLIP_VERTICAL -> flipVertical(frame);
            case ROTATE_180 -> rotate180(frame);
            case ROTATE_90_CLOCKWISE -> rotate90Clockwise(frame);
            case ROTATE_90_COUNTERCLOCKWISE -> rotate90CounterClockwise(frame);
        };
    }

    @Override
    public VideoStreamInfo outputStreamInfo(VideoStreamInfo input) {
        return switch (operation) {
            case ROTATE_90_CLOCKWISE, ROTATE_90_COUNTERCLOCKWISE ->
                    new VideoStreamInfo(input.height(), input.width(), input.framesPerSecond());
            default -> input;
        };
    }

    private static RgbaFrame flipHorizontal(RgbaFrame frame) {
        return remap(frame, frame.width(), frame.height(), (x, y) -> new int[]{frame.width() - 1 - x, y});
    }

    private static RgbaFrame flipVertical(RgbaFrame frame) {
        return remap(frame, frame.width(), frame.height(), (x, y) -> new int[]{x, frame.height() - 1 - y});
    }

    private static RgbaFrame rotate180(RgbaFrame frame) {
        return remap(frame, frame.width(), frame.height(),
                (x, y) -> new int[]{frame.width() - 1 - x, frame.height() - 1 - y});
    }

    private static RgbaFrame rotate90Clockwise(RgbaFrame frame) {
        int outputWidth = frame.height();
        int outputHeight = frame.width();
        return remap(frame, outputWidth, outputHeight,
                (x, y) -> new int[]{y, frame.height() - 1 - x});
    }

    private static RgbaFrame rotate90CounterClockwise(RgbaFrame frame) {
        int outputWidth = frame.height();
        int outputHeight = frame.width();
        return remap(frame, outputWidth, outputHeight,
                (x, y) -> new int[]{frame.width() - 1 - y, x});
    }

    private static RgbaFrame remap(
            RgbaFrame sourceFrame,
            int outputWidth,
            int outputHeight,
            CoordinateMapper mapper) {
        byte[] source = sourceFrame.pixels();
        byte[] output = new byte[outputWidth * outputHeight * 4];
        for (int y = 0; y < outputHeight; y++) {
            for (int x = 0; x < outputWidth; x++) {
                int[] sourceCoordinate = mapper.map(x, y);
                int src = (sourceCoordinate[1] * sourceFrame.width() + sourceCoordinate[0]) * 4;
                int dst = (y * outputWidth + x) * 4;
                System.arraycopy(source, src, output, dst, 4);
            }
        }
        return new RgbaFrame(outputWidth, outputHeight, output);
    }

    @FunctionalInterface
    private interface CoordinateMapper {
        int[] map(int x, int y);
    }
}
