package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;

import java.util.List;
import java.util.Objects;

public final class TransformChain implements FrameTransform {
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

    public int size() {
        return transforms.size();
    }
}
