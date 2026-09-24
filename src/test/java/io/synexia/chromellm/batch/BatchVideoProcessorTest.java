package io.synexia.chromellm.batch;

import io.synexia.chromellm.gpu.CpuImageProcessor;
import io.synexia.chromellm.video.DecoderBackend;
import io.synexia.chromellm.video.VideoEncodingOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchVideoProcessorTest {
    @TempDir
    Path temp;

    @Test
    void refusesInputAsOutput() throws Exception {
        Path same = temp.resolve("same");
        Files.createDirectories(same);
        assertThrows(IllegalArgumentException.class, () -> new BatchVideoProcessor().process(
                same,
                same,
                true,
                false,
                new CpuImageProcessor(),
                () -> (frame, index) -> frame,
                VideoEncodingOptions.defaults(),
                DecoderBackend.PROCESS,
                "ffmpeg"));
    }
}
