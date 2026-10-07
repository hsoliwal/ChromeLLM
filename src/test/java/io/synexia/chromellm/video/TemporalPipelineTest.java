package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.CpuImageProcessor;
import io.synexia.chromellm.gpu.RgbaFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemporalPipelineTest {
    @Test
    void sceneCutDetectsLargeLumaChange() {
        RgbaFrame black = solid(8, 8, 0);
        RgbaFrame white = solid(8, 8, 255);
        SceneCutDetector detector = new SceneCutDetector(0.35, 1);
        assertTrue(detector.isCut(black, white));
        assertFalse(detector.isCut(black, black));
    }

    @Test
    void temporalDenoiseBlendsWithinSceneAndResetsOnCut() {
        var processor = new CpuImageProcessor();
        var transform = new TemporalDenoiseTransform(
                processor,
                0.5f,
                new SceneCutDetector(0.35, 1));

        RgbaFrame first = solid(1, 1, 20);
        RgbaFrame second = solid(1, 1, 40);
        RgbaFrame cut = solid(1, 1, 255);

        assertArrayEquals(first.pixels(), transform.apply(first, 0).pixels());
        int blended = transform.apply(second, 1).pixels()[0] & 255;
        assertTrue(blended >= 29 && blended <= 31);
        assertArrayEquals(cut.pixels(), transform.apply(cut, 2).pixels());
    }

    @Test
    void blockMatcherFindsKnownTranslation() {
        RgbaFrame reference = pointFrame(9, 9, 3, 4);
        RgbaFrame current = pointFrame(9, 9, 5, 3);
        FrameTranslation translation = new BlockMatchingTranslationEstimator(3, 1)
                .estimate(reference, current);
        assertEquals(2, translation.dx());
        assertEquals(-1, translation.dy());
    }

    @Test
    void stabilizationResetsAtStreamBoundary() {
        var transform = new StabilizationTransform(
                new BlockMatchingTranslationEstimator(2, 1),
                new SceneCutDetector(0.35, 1),
                0d);
        RgbaFrame frame = pointFrame(7, 7, 3, 3);
        transform.onStreamStart(new VideoStreamInfo(7, 7, 30));
        assertArrayEquals(frame.pixels(), transform.apply(frame, 0).pixels());
        transform.onStreamEnd();
        assertArrayEquals(frame.pixels(), transform.apply(frame, 0).pixels());
    }

    private static RgbaFrame solid(int width, int height, int value) {
        byte[] pixels = new byte[width * height * 4];
        for (int i = 0; i < pixels.length; i += 4) {
            pixels[i] = pixels[i + 1] = pixels[i + 2] = (byte)value;
            pixels[i + 3] = (byte)255;
        }
        return new RgbaFrame(width, height, pixels);
    }

    private static RgbaFrame pointFrame(int width, int height, int x, int y) {
        byte[] pixels = solid(width, height, 0).pixels();
        int i = (y * width + x) * 4;
        pixels[i] = pixels[i + 1] = pixels[i + 2] = (byte)255;
        return new RgbaFrame(width, height, pixels);
    }
}
