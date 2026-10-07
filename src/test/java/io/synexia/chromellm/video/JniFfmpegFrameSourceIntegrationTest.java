package io.synexia.chromellm.video;

import io.synexia.chromellm.video.nativebridge.JniFfmpegFrameSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "chromellm.native.integration", matches = "true")
class JniFfmpegFrameSourceIntegrationTest {
    @Test
    void nativeFfmpegDecodesGeneratedVideo() throws Exception {
        Path directory = Files.createTempDirectory("chromellm-ffmpeg-jni-");
        Path video = directory.resolve("test.mp4");

        Process process = new ProcessBuilder(List.of(
                "ffmpeg",
                "-v", "error",
                "-y",
                "-f", "lavfi",
                "-i", "testsrc2=size=32x24:rate=5:duration=1",
                "-c:v", "mpeg4",
                "-pix_fmt", "yuv420p",
                video.toAbsolutePath().toString()))
                .inheritIO()
                .start();
        assertEquals(0, process.waitFor(), "ffmpeg should generate the integration fixture");

        try (var source = new JniFfmpegFrameSource(video)) {
            assertEquals(32, source.streamInfo().width());
            assertEquals(24, source.streamInfo().height());
            assertTrue(source.streamInfo().framesPerSecond() > 0d);
            assertTrue(source.backendName().startsWith("ffmpeg-jni:"));

            var frame = source.nextFrame();
            assertNotNull(frame);
            assertEquals(32 * 24 * 4, frame.byteSize());

            source.seekMillis(0);
            assertNotNull(source.nextFrame());
        }
    }
}
