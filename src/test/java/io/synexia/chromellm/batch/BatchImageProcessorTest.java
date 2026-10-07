package io.synexia.chromellm.batch;

import io.synexia.chromellm.gpu.ImageIoFrames;
import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.video.FrameTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BatchImageProcessorTest {
    @TempDir
    Path temp;

    @Test
    void processesRecursivelyWithoutTouchingSources() throws Exception {
        Path input = temp.resolve("input");
        Path nested = input.resolve("nested");
        Path output = temp.resolve("output");
        Files.createDirectories(nested);

        Path source = nested.resolve("sample.png");
        RgbaFrame original = solid(2, 2, 20);
        ImageIoFrames.write(original, source);

        FrameTransform transform = (frame, index) -> solid(frame.width(), frame.height(), 200);
        BatchProcessResult result = new BatchImageProcessor().process(
                input,
                output,
                true,
                false,
                () -> transform,
                1);

        assertEquals(1, result.discovered());
        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
        assertArrayEquals(original.pixels(), ImageIoFrames.read(source).pixels());
        assertEquals(200, ImageIoFrames.read(output.resolve("nested/sample.png")).pixels()[0] & 255);
    }

    @Test
    void refusesInputAsOutput() throws Exception {
        Path input = temp.resolve("same");
        Files.createDirectories(input);
        assertThrows(IllegalArgumentException.class, () -> new BatchImageProcessor().process(
                input,
                input,
                true,
                false,
                () -> (frame, index) -> frame,
                1));
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
