package io.synexia.chromellm.video;

import io.synexia.chromellm.gpu.RgbaFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CubeLutParserTest {
    @TempDir
    Path temp;

    @Test
    void parsesRedFastestIdentityCubeAndInterpolates() throws Exception {
        Path cube = temp.resolve("identity.cube");
        Files.writeString(cube, """
                TITLE "Identity"
                LUT_3D_SIZE 2
                DOMAIN_MIN 0 0 0
                DOMAIN_MAX 1 1 1
                0 0 0
                1 0 0
                0 1 0
                1 1 0
                0 0 1
                1 0 1
                0 1 1
                1 1 1
                """);

        CubeLut lut = new CubeLutParser().parse(cube);
        float[] sample = lut.sample(0.25f, 0.5f, 0.75f);
        assertArrayEquals(new float[]{0.25f, 0.5f, 0.75f}, sample, 0.0001f);

        RgbaFrame frame = new RgbaFrame(1, 1, new byte[]{64, (byte)128, (byte)191, (byte)255});
        RgbaFrame mapped = new Lut3dTransform(lut, 1f).apply(frame, 0);
        assertArrayEquals(frame.pixels(), mapped.pixels());
    }

    @Test
    void rejectsTruncatedCube() throws Exception {
        Path cube = temp.resolve("bad.cube");
        Files.writeString(cube, "LUT_3D_SIZE 2\n0 0 0\n");
        assertThrows(IllegalArgumentException.class, () -> new CubeLutParser().parse(cube));
    }
}
