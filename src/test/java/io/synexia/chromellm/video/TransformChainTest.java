package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TransformChainTest {
    @Test
    void appliesTransformsInOrder() {
        AtomicInteger sequence = new AtomicInteger();
        FrameTransform first = (frame, index) -> {
            assertEquals(0, sequence.getAndIncrement());
            return frame;
        };
        FrameTransform second = (frame, index) -> {
            assertEquals(1, sequence.getAndIncrement());
            return frame;
        };

        var frame = new RgbaFrame(1, 1, new byte[]{1, 2, 3, 4});
        assertSame(frame, TransformChain.of(first, second).apply(frame, 7));
        assertEquals(2, sequence.get());
    }
}
