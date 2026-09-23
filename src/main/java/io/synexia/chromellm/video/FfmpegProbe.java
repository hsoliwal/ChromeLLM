package io.synexia.chromellm.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public final class FfmpegProbe {
    private final String executable;

    public FfmpegProbe() {
        this(System.getProperty("chromellm.ffprobe", "ffprobe"));
    }

    public FfmpegProbe(String executable) {
        this.executable = executable;
    }

    public VideoStreamInfo probe(Path input) throws IOException, InterruptedException {
        List<String> command = List.of(
                executable,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("ffprobe failed with exit code " + exit);

        String[] lines = output.lines().map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (lines.length < 3) throw new IOException("Unexpected ffprobe output: " + output);

        int width = Integer.parseInt(lines[0]);
        int height = Integer.parseInt(lines[1]);
        double fps = parseRate(lines[2]);
        return new VideoStreamInfo(width, height, fps);
    }

    static double parseRate(String value) {
        String text = value.trim();
        int slash = text.indexOf('/');
        if (slash < 0) return Double.parseDouble(text);
        double numerator = Double.parseDouble(text.substring(0, slash));
        double denominator = Double.parseDouble(text.substring(slash + 1));
        if (denominator == 0d) throw new IllegalArgumentException("invalid frame rate: " + value);
        return numerator / denominator;
    }
}
