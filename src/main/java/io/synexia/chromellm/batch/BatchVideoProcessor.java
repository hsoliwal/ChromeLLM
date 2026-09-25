package io.synexia.chromellm.batch;

import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.video.DecoderBackend;
import io.synexia.chromellm.video.FfmpegProbe;
import io.synexia.chromellm.video.FfmpegVideoProcessor;
import io.synexia.chromellm.video.FrameTransform;
import io.synexia.chromellm.video.ProcessingControl;
import io.synexia.chromellm.video.ProcessingProgressListener;
import io.synexia.chromellm.video.VideoEncodingOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class BatchVideoProcessor {
    private static final Set<String> EXTENSIONS = Set.of(
            "mp4", "mkv", "mov", "avi", "webm", "m4v");

    public BatchProcessResult process(
            Path inputDirectory,
            Path outputDirectory,
            boolean recursive,
            boolean overwrite,
            ImageProcessor processor,
            Supplier<FrameTransform> transformFactory,
            VideoEncodingOptions encodingOptions,
            DecoderBackend decoderBackend,
            String ffmpeg) throws IOException {
        Path input = inputDirectory.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) throw new IllegalArgumentException("inputDirectory is not a directory: " + input);
        if (input.equals(output)) throw new IllegalArgumentException("input and output directories must differ");

        List<Path> files = discover(input, recursive);
        Files.createDirectories(output);
        Instant start = Instant.now();
        List<BatchFailure> failures = new ArrayList<>();
        int succeeded = 0;

        for (Path source : files) {
            Path relative = input.relativize(source);
            Path target = output.resolve(relative).normalize();
            if (!target.startsWith(output)) {
                failures.add(new BatchFailure(source, "resolved output escaped output directory"));
                continue;
            }
            if (Files.exists(target) && !overwrite) {
                failures.add(new BatchFailure(source, "output exists and overwrite=false: " + target));
                continue;
            }

            try {
                Files.createDirectories(target.getParent());
                FrameTransform transform = transformFactory.get();
                if (transform == null) throw new IllegalStateException("transformFactory returned null");
                new FfmpegVideoProcessor(
                        ffmpeg,
                        new FfmpegProbe(),
                        decoderBackend)
                        .process(
                                source,
                                target,
                                processor,
                                transform,
                                encodingOptions,
                                ProcessingProgressListener.NONE,
                                ProcessingControl.NEVER_CANCELLED,
                                120);
                succeeded++;
            } catch (Exception exception) {
                failures.add(new BatchFailure(source, exception.toString()));
            }
        }

        return new BatchProcessResult(
                files.size(),
                succeeded,
                failures,
                Duration.between(start, Instant.now()));
    }

    private static List<Path> discover(Path root, boolean recursive) throws IOException {
        try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(BatchVideoProcessor::supported)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
