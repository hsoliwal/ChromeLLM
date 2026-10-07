package io.synexia.chromellm.video;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VideoEncodingOptionsTest {
    @Test
    void buildsConfigurableH265AacCommand() {
        var options = new VideoEncodingOptions(
                VideoCodec.H265,
                "slow",
                20,
                "yuv420p",
                AudioMode.AAC,
                256,
                4,
                List.of("-movflags", "+faststart"));

        List<String> command = FfmpegRgbaEncoder.command(
                "ffmpeg",
                Path.of("input.mp4"),
                Path.of("output.mp4"),
                new VideoStreamInfo(1920, 1080, 30),
                options);

        assertContainsPair(command, "-c:v", "libx265");
        assertContainsPair(command, "-preset", "slow");
        assertContainsPair(command, "-crf", "20");
        assertContainsPair(command, "-c:a", "aac");
        assertContainsPair(command, "-b:a", "256k");
        assertContainsPair(command, "-threads", "4");
        assertTrue(command.contains("+faststart"));
    }

    @Test
    void rejectsOddYuv420GeometryBeforeStartingFfmpeg() {
        assertThrows(IllegalArgumentException.class, () -> FfmpegRgbaEncoder.command(
                "ffmpeg",
                Path.of("input.mp4"),
                Path.of("output.mp4"),
                new VideoStreamInfo(1919, 1080, 30),
                VideoEncodingOptions.defaults()));
    }

    @Test
    void noneAudioModeDisablesAudioMapping() {
        var defaults = VideoEncodingOptions.defaults();
        var options = new VideoEncodingOptions(
                defaults.videoCodec(),
                defaults.preset(),
                defaults.crf(),
                defaults.pixelFormat(),
                AudioMode.NONE,
                defaults.audioBitrateKbps(),
                defaults.threads(),
                List.of());
        List<String> command = FfmpegRgbaEncoder.command(
                "ffmpeg",
                Path.of("input.mp4"),
                Path.of("output.mp4"),
                new VideoStreamInfo(1280, 720, 24),
                options);
        assertTrue(command.contains("-an"));
    }

    @Test
    void cancellationControlThrowsDedicatedException() {
        ProcessingControl control = () -> true;
        assertThrows(ProcessingCancelledException.class, control::checkCancelled);
    }

    private static void assertContainsPair(List<String> command, String key, String value) {
        int index = command.indexOf(key);
        assertTrue(index >= 0, "missing " + key);
        assertTrue(index + 1 < command.size(), "missing value for " + key);
        assertEquals(value, command.get(index + 1));
    }
}
