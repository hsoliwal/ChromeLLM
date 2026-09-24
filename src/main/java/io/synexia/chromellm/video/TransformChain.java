package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.List;
import java.util.Objects;

public final class TransformChain implements FrameTransform, FrameTransformLifecycle, FrameTransformShape {
    private final List<FrameTransform> transforms;

    public TransformChain(List<FrameTransform> transforms) {
        this.transforms = List.copyOf(Objects.requireNonNull(transforms, "transforms"));
    }

    public static TransformChain of(FrameTransform... transforms) {
        return new TransformChain(List.of(transforms));
    }

    @Override
    public RgbaFrame apply(RgbaFrame frame, long frameIndex) {
        RgbaFrame current = Objects.requireNonNull(frame, "frame");
        for (FrameTransform transform : transforms) {
            current = Objects.requireNonNull(
                    transform.apply(current, frameIndex),
                    "transform returned null");
        }
        return current;
    }

    @Override
    public VideoStreamInfo outputStreamInfo(VideoStreamInfo input) {
        VideoStreamInfo current = Objects.requireNonNull(input, "input");
        for (FrameTransform transform : transforms) {
            if (transform instanceof FrameTransformShape shape) {
                current = Objects.requireNonNull(shape.outputStreamInfo(current), "shape transform returned null");
            }
        }
        return current;
    }

    @Override
    public void onStreamStart(VideoStreamInfo streamInfo) {
        VideoStreamInfo current = Objects.requireNonNull(streamInfo, "streamInfo");
        for (FrameTransform transform : transforms) {
            if (transform instanceof FrameTransformLifecycle lifecycle) {
                lifecycle.onStreamStart(current);
            }
            if (transform instanceof FrameTransformShape shape) {
                current = Objects.requireNonNull(shape.outputStreamInfo(current), "shape transform returned null");
            }
        }
    }

    @Override
    public void reset() {
        for (FrameTransform transform : transforms) {
            if (transform instanceof FrameTransformLifecycle lifecycle) {
                lifecycle.reset();
            }
        }
    }

    @Override
    public void onStreamEnd() {
        for (int i = transforms.size() - 1; i >= 0; i--) {
            FrameTransform transform = transforms.get(i);
            if (transform instanceof FrameTransformLifecycle lifecycle) {
                lifecycle.onStreamEnd();
            }
        }
    }

    public int size() {
        return transforms.size();
    }

    public List<FrameTransform> transforms() {
        return transforms;
    }
}
