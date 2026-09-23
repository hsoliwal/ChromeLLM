package io.synexia.chromellm.vision;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.vision.nativebridge.OpenCvVisionProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "chromellm.native.integration", matches = "true")
class OpenCvVisionProcessorIntegrationTest {
    @Test
    void nativeOpenCvCanInpaintAndInterpolate() {
        RgbaFrame first = gradient(32, 32, 0);
        RgbaFrame second = gradient(32, 32, 20);
        byte[] maskBytes = new byte[32 * 32];
        for (int y = 12; y < 20; y++) {
            for (int x = 12; x < 20; x++) {
                maskBytes[y * 32 + x] = (byte)255;
            }
        }

        try (VisionProcessor processor = new OpenCvVisionProcessor()) {
            assertTrue(processor.backendName().startsWith("opencv-jni:"));

            RgbaFrame repaired = processor.inpaint(
                    first,
                    new AlphaMask(32, 32, maskBytes),
                    3f);
            assertEquals(first.byteSize(), repaired.byteSize());

            RgbaFrame middle = processor.interpolate(first, second, 0.5f);
            assertEquals(first.byteSize(), middle.byteSize());
        }
    }

    private static RgbaFrame gradient(int width, int height, int offset) {
        byte[] pixels = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                pixels[i] = (byte)((x * 7 + offset) & 255);
                pixels[i + 1] = (byte)((y * 7 + offset) & 255);
                pixels[i + 2] = (byte)(((x + y) * 3 + offset) & 255);
                pixels[i + 3] = (byte)255;
            }
        }
        return new RgbaFrame(width, height, pixels);
    }
}
