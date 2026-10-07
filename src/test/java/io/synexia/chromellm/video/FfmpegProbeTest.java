package io.synexia.chromellm.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FfmpegProbeTest {
    @Test
    void parsesRationalFrameRate() {
        assertEquals(29.97002997, FfmpegProbe.parseRate("30000/1001"), 0.00000001);
    }

    @Test
    void parsesDecimalFrameRate() {
        assertEquals(60.0, FfmpegProbe.parseRate("60"), 0.0);
    }

    @Test
    void rejectsZeroDenominator() {
        assertThrows(IllegalArgumentException.class, () -> FfmpegProbe.parseRate("1/0"));
    }

    @Test
    void computesRawRgbaFrameBytes() {
        assertEquals(1920 * 1080 * 4, new VideoStreamInfo(1920, 1080, 30).rgbaFrameBytes());
    }
}
