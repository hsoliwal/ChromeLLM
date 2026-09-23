package io.synexia.chromellm.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuImageProcessorTest {
    @Test
    void invertChangesRgbAndPreservesAlpha() {
        var input = new RgbaFrame(1, 1, new byte[]{10, 20, 30, (byte)200});
        var result = new CpuImageProcessor().process(input, PixelOperation.INVERT, PixelParameters.none());
        byte[] p = result.pixels();
        assertEquals(245, p[0] & 255);
        assertEquals(235, p[1] & 255);
        assertEquals(225, p[2] & 255);
        assertEquals(200, p[3] & 255);
    }

    @Test
    void blendUsesOverlayAlpha() {
        var base = new RgbaFrame(1, 1, new byte[]{0, 0, 0, (byte)255});
        var overlay = new RgbaFrame(1, 1, new byte[]{(byte)255, (byte)255, (byte)255, (byte)128});
        var result = new CpuImageProcessor().blend(base, overlay, 1f);
        byte[] p = result.pixels();
        assertTrue((p[0] & 255) >= 127 && (p[0] & 255) <= 128);
        assertEquals(255, p[3] & 255);
    }

    @Test
    void objectCanBePlacedAtCoordinates() {
        var base = solid(3, 2, 0, 0, 0, 255);
        var object = solid(1, 1, 255, 0, 0, 255);
        var result = ObjectComposer.place(new CpuImageProcessor(), base, object, 2, 1, 1f);
        byte[] p = result.pixels();
        int i = (1 * 3 + 2) * 4;
        assertEquals(255, p[i] & 255);
        assertEquals(0, p[i + 1] & 255);
        assertEquals(0, p[i + 2] & 255);
    }

    @Test
    void proceduralNoiseIsDeterministicForSeed() {
        var processor = new CpuImageProcessor();
        var a = processor.generate(8, 8, PixelGenerator.NOISE, 99L, PixelParameters.none());
        var b = processor.generate(8, 8, PixelGenerator.NOISE, 99L, PixelParameters.none());
        assertArrayEquals(a.pixels(), b.pixels());
    }

    @Test
    void rgbaFrameDefensivelyCopiesPixels() {
        byte[] source = new byte[]{1, 2, 3, 4};
        var frame = new RgbaFrame(1, 1, source);
        source[0] = 99;
        assertEquals(1, frame.pixels()[0]);
        byte[] copy = frame.pixels();
        copy[1] = 99;
        assertEquals(2, frame.pixels()[1]);
    }

    private static RgbaFrame solid(int width, int height, int r, int g, int b, int a) {
        byte[] pixels = new byte[width * height * 4];
        for (int i = 0; i < pixels.length; i += 4) {
            pixels[i] = (byte)r;
            pixels[i + 1] = (byte)g;
            pixels[i + 2] = (byte)b;
            pixels[i + 3] = (byte)a;
        }
        return new RgbaFrame(width, height, pixels);
    }
}
