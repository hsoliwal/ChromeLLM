package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.AlphaMasks;
import io.synexia.chromellm.gpu.RgbaFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompositingTransformsTest {
    @Test
    void regionTransformOnlyChangesSelectedRegion() {
        RgbaFrame base = solid(4, 2, 10);
        FrameTransform brighten = (frame, index) -> solid(frame.width(), frame.height(), 200);
        RgbaFrame result = new RegionTransform(new FrameRegion(1, 0, 2, 2), brighten).apply(base, 0);
        byte[] pixels = result.pixels();
        assertEquals(10, pixels[0] & 255);
        assertEquals(200, pixels[(0 * 4 + 1) * 4] & 255);
        assertEquals(200, pixels[(1 * 4 + 2) * 4] & 255);
        assertEquals(10, pixels[(1 * 4 + 3) * 4] & 255);
    }

    @Test
    void maskedTransformUsesMaskAsBlendWeight() {
        RgbaFrame base = solid(2, 1, 0);
        FrameTransform white = (frame, index) -> solid(2, 1, 255);
        AlphaMask mask = new AlphaMask(2, 1, new byte[]{0, (byte)255});
        RgbaFrame result = new MaskedTransform(white, (frame, index) -> mask).apply(base, 0);
        byte[] pixels = result.pixels();
        assertEquals(0, pixels[0] & 255);
        assertEquals(255, pixels[4] & 255);
    }

    @Test
    void frameRangeAppliesOnlyInsideBounds() {
        FrameTransform white = (frame, index) -> solid(frame.width(), frame.height(), 255);
        FrameTransform ranged = new FrameRangeTransform(2, 4, white);
        RgbaFrame black = solid(1, 1, 0);
        assertEquals(0, ranged.apply(black, 1).pixels()[0] & 255);
        assertEquals(255, ranged.apply(black, 2).pixels()[0] & 255);
        assertEquals(0, ranged.apply(black, 4).pixels()[0] & 255);
    }

    @Test
    void maskUtilitiesInvertThresholdAndFeather() {
        AlphaMask mask = new AlphaMask(3, 1, new byte[]{0, (byte)128, (byte)255});
        assertArrayEquals(new byte[]{(byte)255, (byte)127, 0}, AlphaMasks.invert(mask).values());
        assertArrayEquals(new byte[]{0, (byte)255, (byte)255}, AlphaMasks.threshold(mask, 128).values());
        AlphaMask feathered = AlphaMasks.feather(new AlphaMask(5, 1, new byte[]{0,0,(byte)255,0,0}), 1);
        assertTrue((feathered.values()[1] & 255) > 0);
        assertTrue((feathered.values()[2] & 255) < 255);
    }

    private static RgbaFrame solid(int width, int height, int value) {
        byte[] pixels = new byte[width * height * 4];
        for (int i = 0; i < pixels.length; i += 4) {
            pixels[i] = pixels[i + 1] = pixels[i + 2] = (byte)value;
            pixels[i + 3] = (byte)255;
        }
        return new RgbaFrame(width, height, pixels);
    }
}
