package io.synexia.chromellm.preset;

import io.synexia.chromellm.gpu.CpuImageProcessor;
import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.video.AudioMode;
import io.synexia.chromellm.video.VideoCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MediaPresetLoaderTest {
    @TempDir
    Path temp;

    @Test
    void compilesPipelineAutomationAndEncoding() throws Exception {
        Path preset = temp.resolve("preset.json");
        Files.writeString(preset, """
                {
                  "version": 1,
                  "pipeline": "flip-horizontal",
                  "automation": [
                    {
                      "operation": "brightness-contrast",
                      "p0": [
                        {"frame": 0, "value": 0.0, "easing": "linear"},
                        {"frame": 10, "value": 0.2, "easing": "linear"}
                      ],
                      "p1": [
                        {"frame": 0, "value": 1.0, "easing": "hold"}
                      ]
                    }
                  ],
                  "encoding": {
                    "videoCodec": "h265",
                    "preset": "medium",
                    "crf": 21,
                    "audioMode": "aac",
                    "audioBitrateKbps": 256,
                    "threads": 2
                  },
                  "progressEveryFrames": 12
                }
                """);

        CompiledMediaPreset compiled = new MediaPresetLoader().load(preset, new CpuImageProcessor());
        assertEquals(VideoCodec.H265, compiled.encodingOptions().videoCodec());
        assertEquals(AudioMode.AAC, compiled.encodingOptions().audioMode());
        assertEquals(21, compiled.encodingOptions().crf());
        assertEquals(12, compiled.progressEveryFrames());

        RgbaFrame input = new RgbaFrame(2, 1, new byte[]{
                10, 0, 0, (byte)255,
                100, 0, 0, (byte)255
        });
        RgbaFrame frame0 = compiled.transform().apply(input, 0);
        assertEquals(100, frame0.pixels()[0] & 255);
        RgbaFrame frame10 = compiled.transform().apply(input, 10);
        assertTrue((frame10.pixels()[0] & 255) > 100);
    }

    @Test
    void rejectsUnsupportedPresetVersion() throws Exception {
        Path preset = temp.resolve("preset.json");
        Files.writeString(preset, """
                {"version": 99, "pipeline": "grayscale"}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> new MediaPresetLoader().load(preset, new CpuImageProcessor()));
    }
}
