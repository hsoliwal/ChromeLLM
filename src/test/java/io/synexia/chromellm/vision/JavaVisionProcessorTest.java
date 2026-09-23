package io.synexia.chromellm.vision;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.RgbaFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaVisionProcessorTest {
    @Test
    void grabCutFallbackProducesRectangleMask() {
        var image = solid(4, 4, 10, 20, 30, 255);
        var mask = new JavaVisionProcessor().grabCut(image, 1, 1, 2, 2, 1);
        byte[] values = mask.values();

        assertEquals(0, values[0] & 255);
        assertEquals(255, values[1 * 4 + 1] & 255);
        assertEquals(255, values[2 * 4 + 2] & 255);
        assertEquals(0, values[3 * 4 + 3] & 255);
    }

    @Test
    void interpolationFallbackBlendsFrames() {
        var black = solid(1, 1, 0, 0, 0, 255);
        var white = solid(1, 1, 255, 255, 255, 255);
        byte[] pixel = new JavaVisionProcessor().interpolate(black, white, 0.5f).pixels();
        assertTrue((pixel[0] & 255) >= 127 && (pixel[0] & 255) <= 128);
    }

    @Test
    void inpaintFallbackKeepsDimensions() {
        var image = solid(8, 8, 100, 100, 100, 255);
        byte[] maskBytes = new byte[64];
        maskBytes[4 * 8 + 4] = (byte)255;
        var result = new JavaVisionProcessor().inpaint(image, new AlphaMask(8, 8, maskBytes), 2f);
        assertEquals(8, result.width());
        assertEquals(8, result.height());
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
