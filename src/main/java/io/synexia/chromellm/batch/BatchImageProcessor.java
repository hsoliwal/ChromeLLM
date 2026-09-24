package io.synexia.chromellm.batch;

import io.synexia.chromellm.gpu.ImageIoFrames;
import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.video.FrameTransform;
import io.synexia.chromellm.video.FrameTransformLifecycle;
import io.synexia.chromellm.video.VideoStreamInfo;

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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class BatchImageProcessor {
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp", "gif");

    public BatchProcessResult process(
            Path inputDirectory,
            Path outputDirectory,
            boolean recursive,
            boolean overwrite,
            Supplier<FrameTransform> transformFactory,
            int parallelism) throws IOException, InterruptedException {
        Path input = inputDirectory.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(input)) throw new IllegalArgumentException("inputDirectory is not a directory: " + input);
        if (input.equals(output)) throw new IllegalArgumentException("input and output directories must differ");
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be positive");

        List<Path> files = discover(input, recursive);
        Files.createDirectories(output);
        Instant start = Instant.now();

        if (parallelism == 1) {
            List<BatchFailure> failures = new ArrayList<>();
            int succeeded = 0;
            for (Path source : files) {
                BatchFailure failure = processOne(input, output, source, overwrite, transformFactory);
                if (failure == null) succeeded++;
                else failures.add(failure);
            }
            return new BatchProcessResult(files.size(), succeeded, failures, Duration.between(start, Instant.now()));
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(parallelism)) {
            List<Callable<BatchFailure>> tasks = files.stream()
                    .<Callable<BatchFailure>>map(source -> () -> processOne(
                            input, output, source, overwrite, transformFactory))
                    .toList();
            List<Future<BatchFailure>> futures = executor.invokeAll(tasks);
            List<BatchFailure> failures = new ArrayList<>();
            for (Future<BatchFailure> future : futures) {
                try {
                    BatchFailure failure = future.get();
                    if (failure != null) failures.add(failure);
                } catch (java.util.concurrent.ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    failures.add(new BatchFailure(input, cause == null ? exception.toString() : cause.toString()));
                }
            }
            return new BatchProcessResult(
                    files.size(),
                    files.size() - failures.size(),
                    failures,
                    Duration.between(start, Instant.now()));
        }
    }

    private static BatchFailure processOne(
            Path inputRoot,
            Path outputRoot,
            Path source,
            boolean overwrite,
            Supplier<FrameTransform> transformFactory) {
        Path relative = inputRoot.relativize(source);
        Path target = outputRoot.resolve(relative).normalize();
        if (!target.startsWith(outputRoot)) {
            return new BatchFailure(source, "resolved output escaped output directory");
        }

        try {
            if (Files.exists(target) && !overwrite) {
                return new BatchFailure(source, "output exists and overwrite=false: " + target);
            }
            Files.createDirectories(target.getParent());
            RgbaFrame frame = ImageIoFrames.read(source);
            FrameTransform transform = transformFactory.get();
            if (transform == null) throw new IllegalStateException("transformFactory returned null");
            FrameTransformLifecycle lifecycle = transform instanceof FrameTransformLifecycle value ? value : null;
            if (lifecycle != null) {
                lifecycle.reset();
                lifecycle.onStreamStart(new VideoStreamInfo(frame.width(), frame.height(), 1d));
            }
            try {
                RgbaFrame result = transform.apply(frame, 0L);
                ImageIoFrames.write(result, target);
            } finally {
                if (lifecycle != null) lifecycle.onStreamEnd();
            }
            return null;
        } catch (Exception exception) {
            return new BatchFailure(source, exception.toString());
        }
    }

    private static List<Path> discover(Path root, boolean recursive) throws IOException {
        try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(BatchImageProcessor::supported)
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
