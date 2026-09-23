package io.synexia.chromellm.gpu.cli;

import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.GpuProcessorFactory;
import io.synexia.chromellm.gpu.ImageIoFrames;
import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.MaskOps;
import io.synexia.chromellm.gpu.ObjectComposer;
import io.synexia.chromellm.gpu.PixelGenerator;
import io.synexia.chromellm.gpu.PixelOperation;
import io.synexia.chromellm.gpu.PixelParameters;
import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.video.FfmpegVideoProcessor;
import io.synexia.chromellm.video.GpuFrameTransform;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class GpuMain {
    private GpuMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        Map<String, String> options = parse(args, 1);
        GpuProcessorFactory.Backend backend = enumValue(
                GpuProcessorFactory.Backend.class,
                options.getOrDefault("backend", "auto"));

        try (ImageProcessor processor = GpuProcessorFactory.open(backend)) {
            switch (command) {
                case "image" -> image(processor, options);
                case "generate" -> generate(processor, options);
                case "place" -> place(processor, options);
                case "remove" -> remove(processor, options);
                case "video" -> video(processor, options);
                case "info" -> info(processor);
                default -> throw new IllegalArgumentException("unknown command: " + command);
            }
        }
    }

    private static void image(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path output = requiredPath(options, "output");
        PixelOperation operation = enumValue(PixelOperation.class, required(options, "op"));
        PixelParameters parameters = parameters(options);
        RgbaFrame result = processor.process(ImageIoFrames.read(input), operation, parameters);
        ImageIoFrames.write(result, output);
        completed(processor, output);
    }

    private static void generate(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path output = requiredPath(options, "output");
        int width = intValue(options, "width", 1024);
        int height = intValue(options, "height", 1024);
        long seed = longValue(options, "seed", 1L);
        PixelGenerator generator = enumValue(
                PixelGenerator.class,
                options.getOrDefault("generator", "plasma"));
        RgbaFrame result = processor.generate(width, height, generator, seed, parameters(options));
        ImageIoFrames.write(result, output);
        completed(processor, output);
    }

    private static void place(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path object = requiredPath(options, "object");
        Path output = requiredPath(options, "output");
        int x = intValue(options, "x", 0);
        int y = intValue(options, "y", 0);
        float opacity = floatValue(options, "opacity", 1f);
        RgbaFrame result = ObjectComposer.place(
                processor,
                ImageIoFrames.read(input),
                ImageIoFrames.read(object),
                x,
                y,
                opacity);
        ImageIoFrames.write(result, output);
        completed(processor, output);
    }

    private static void remove(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path mask = requiredPath(options, "mask");
        Path output = requiredPath(options, "output");
        int radius = intValue(options, "radius", 8);
        int passes = intValue(options, "passes", 4);
        RgbaFrame source = ImageIoFrames.read(input);
        AlphaMask alphaMask = AlphaMask.fromFrameLuma(ImageIoFrames.read(mask));
        RgbaFrame result = MaskOps.removeApprox(processor, source, alphaMask, radius, passes);
        ImageIoFrames.write(result, output);
        completed(processor, output);
    }

    private static void video(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path output = requiredPath(options, "output");
        PixelOperation operation = enumValue(PixelOperation.class, required(options, "op"));
        PixelParameters parameters = parameters(options);
        var result = new FfmpegVideoProcessor().process(
                input,
                output,
                processor,
                new GpuFrameTransform(processor, operation, parameters));
        System.out.printf(
                Locale.ROOT,
                "processed %d frames to %s using %s in %.3fs%n",
                result.framesProcessed(),
                result.output(),
                result.processorBackend(),
                result.elapsed().toNanos() / 1_000_000_000.0);
    }

    private static void info(ImageProcessor processor) {
        System.out.println("backend=" + processor.backendName());
        System.out.println("hardwareAccelerated=" + processor.hardwareAccelerated());
    }

    private static PixelParameters parameters(Map<String, String> options) {
        return new PixelParameters(
                floatValue(options, "p0", 0f),
                floatValue(options, "p1", 0f),
                floatValue(options, "p2", 0f),
                floatValue(options, "p3", 0f));
    }

    private static Map<String, String> parse(String[] args, int start) {
        Map<String, String> values = new HashMap<>();
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) throw new IllegalArgumentException("expected --option, got " + token);
            String key = token.substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                values.put(key, "true");
            } else {
                values.put(key, args[++i]);
            }
        }
        return Map.copyOf(values);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + key);
        return value;
    }

    private static Path requiredPath(Map<String, String> options, String key) {
        return Path.of(required(options, key));
    }

    private static int intValue(Map<String, String> options, String key, int fallback) {
        return options.containsKey(key) ? Integer.parseInt(options.get(key)) : fallback;
    }

    private static long longValue(Map<String, String> options, String key, long fallback) {
        return options.containsKey(key) ? Long.parseLong(options.get(key)) : fallback;
    }

    private static float floatValue(Map<String, String> options, String key, float fallback) {
        return options.containsKey(key) ? Float.parseFloat(options.get(key)) : fallback;
    }

    private static void completed(ImageProcessor processor, Path output) {
        System.out.println("output=" + output.toAbsolutePath());
        System.out.println("backend=" + processor.backendName());
        System.out.println("hardwareAccelerated=" + processor.hardwareAccelerated());
    }

    private static void usage() {
        System.out.println("""
                ChromeLLM GPU image/video CLI

                Commands:
                  info --backend auto|cpu|jna|jni
                  image --input in.png --output out.png --op invert|grayscale|brightness-contrast|gamma|threshold|channel-scale|sobel-edge|box-blur [--p0 N --p1 N --p2 N --p3 N] [--backend auto]
                  generate --output out.png --width 1024 --height 1024 --generator solid|gradient|noise|plasma|checkerboard [--seed N] [--p0 N ...] [--backend auto]
                  place --input base.png --object object.png --x 100 --y 100 --opacity 1 --output out.png [--backend auto]
                  remove --input image.png --mask mask.png --radius 8 --passes 4 --output out.png [--backend auto]
                  video --input in.mp4 --output out.mp4 --op grayscale [--p0 N ...] [--backend auto]

                AUTO prefers OpenCL through JNA, then JNI, then the Java CPU fallback.
                """);
    }
}
