package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GeometryTransformTest {
    @Test
    void clockwiseRotationSwapsDimensionsAndPreservesPixelOrder() {
        RgbaFrame input = twoPixelFrame();
        var transform = new GeometryTransform(GeometryOperation.ROTATE_90_CLOCKWISE);
        RgbaFrame output = transform.apply(input, 0);

        assertEquals(1, output.width());
        assertEquals(2, output.height());
        assertEquals(2, transform.outputStreamInfo(new VideoStreamInfo(2, 1, 30)).height());
        byte[] pixels = output.pixels();
        assertEquals(255, pixels[0] & 255);
        assertEquals(0, pixels[1] & 255);
        assertEquals(0, pixels[2] & 255);
        assertEquals(0, pixels[4] & 255);
        assertEquals(255, pixels[5] & 255);
    }

    @Test
    void transformChainPropagatesChangingGeometry() {
        TransformChain chain = TransformChain.of(
                new ResizeTransform(8, 6),
                new CropTransform(new FrameRegion(1, 1, 4, 2)),
                new GeometryTransform(GeometryOperation.ROTATE_90_CLOCKWISE));
        VideoStreamInfo output = chain.outputStreamInfo(new VideoStreamInfo(16, 9, 24));
        assertEquals(2, output.width());
        assertEquals(4, output.height());
        assertEquals(24, output.framesPerSecond(), 0d);
    }

    @Test
    void resizeProducesRequestedDimensions() {
        RgbaFrame output = new ResizeTransform(5, 3).apply(twoPixelFrame(), 0);
        assertEquals(5, output.width());
        assertEquals(3, output.height());
    }

    private static RgbaFrame twoPixelFrame() {
        return new RgbaFrame(2, 1, new byte[]{
                (byte)255, 0, 0, (byte)255,
                0, (byte)255, 0, (byte)255
        });
    }
}
