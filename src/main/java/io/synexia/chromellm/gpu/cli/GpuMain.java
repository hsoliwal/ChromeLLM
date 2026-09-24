package io.synexia.chromellm.gpu.cli;

import io.synexia.chromellm.batch.BatchImageProcessor;
import io.synexia.chromellm.gpu.AlphaMask;
import io.synexia.chromellm.gpu.GpuProcessorFactory;
import io.synexia.chromellm.gpu.ImageIoFrames;
import io.synexia.chromellm.gpu.ImageProcessor;
import io.synexia.chromellm.gpu.MaskOps;
import io.synexia.chromellm.gpu.ObjectComposer;
import io.synexia.chromellm.gpu.PixelGenerator;
import io.synexia.chromellm.gpu.PixelParameters;
import io.synexia.chromellm.gpu.RgbaFrame;
import io.synexia.chromellm.preset.CompiledMediaPreset;
import io.synexia.chromellm.preset.MediaPresetLoader;
import io.synexia.chromellm.video.AudioMode;
import io.synexia.chromellm.video.DecoderBackend;
import io.synexia.chromellm.video.FfmpegProbe;
import io.synexia.chromellm.video.FfmpegVideoInterpolator;
import io.synexia.chromellm.video.FfmpegVideoProcessor;
import io.synexia.chromellm.video.FrameTransform;
import io.synexia.chromellm.video.ProcessingControl;
import io.synexia.chromellm.video.ProcessingProgress;
import io.synexia.chromellm.video.ProcessingProgressListener;
import io.synexia.chromellm.video.SceneCutDetector;
import io.synexia.chromellm.video.TransformSpecParser;
import io.synexia.chromellm.video.VideoAnalyzer;
import io.synexia.chromellm.video.VideoCodec;
import io.synexia.chromellm.video.VideoEncodingOptions;
import io.synexia.chromellm.vision.VisionProcessor;
import io.synexia.chromellm.vision.VisionProcessorFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class GpuMain {
    private GpuMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        Map<String, String> options = parse(args, 1);

        if (isVisionOnlyCommand(command)) {
            runVisionCommand(command, options);
            return;
        }
        if ("analyze-video".equals(command)) {
            analyzeVideo(options);
            return;
        }

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
                case "batch-image" -> batchImage(processor, options);
                case "info" -> info(processor);
                default -> throw new IllegalArgumentException("unknown command: " + command);
            }
        }
    }

    private static boolean isVisionOnlyCommand(String command) {
        return switch (command) {
            case "inpaint", "clone", "grabcut", "interpolate-image", "video-interpolate" -> true;
            default -> false;
        };
    }

    private static void runVisionCommand(String command, Map<String, String> options) throws Exception {
        VisionProcessorFactory.Backend backend = enumValue(
                VisionProcessorFactory.Backend.class,
                options.getOrDefault("vision-backend", "auto"));

        try (VisionProcessor vision = VisionProcessorFactory.open(backend)) {
            switch (command) {
                case "inpaint" -> inpaint(vision, options);
                case "clone" -> cloneObject(vision, options);
                case "grabcut" -> grabCut(vision, options);
                case "interpolate-image" -> interpolateImage(vision, options);
                case "video-interpolate" -> interpolateVideo(vision, options);
                default -> throw new IllegalArgumentException("unknown vision command: " + command);
            }
        }
    }

    private static void image(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path output = requiredPath(options, "output");
        CompiledMediaPreset preset = loadPreset(processor, options);
        FrameTransform selected = preset == null ? transform(processor, options) : preset.transform();
        RgbaFrame result = selected.apply(ImageIoFrames.read(input), 0L);
        ImageIoFrames.write(result, output);
        completed(processor.backendName(), output);
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
        completed(processor.backendName(), output);
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
        completed(processor.backendName(), output);
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
        completed(processor.backendName(), output);
    }

    private static void inpaint(VisionProcessor vision, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path mask = requiredPath(options, "mask");
        Path output = requiredPath(options, "output");
        float radius = floatValue(options, "radius", 3f);
        RgbaFrame source = ImageIoFrames.read(input);
        AlphaMask alphaMask = AlphaMask.fromFrameLuma(ImageIoFrames.read(mask));
        RgbaFrame result = vision.inpaint(source, alphaMask, radius);
        ImageIoFrames.write(result, output);
        completed(vision.backendName(), output);
    }

    private static void cloneObject(VisionProcessor vision, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path object = requiredPath(options, "object");
        Path output = requiredPath(options, "output");
        RgbaFrame background = ImageIoFrames.read(input);
        RgbaFrame foreground = ImageIoFrames.read(object);
        int centerX = intValue(options, "center-x", background.width() / 2);
        int centerY = intValue(options, "center-y", background.height() / 2);
        RgbaFrame result = vision.seamlessClone(background, foreground, centerX, centerY);
        ImageIoFrames.write(result, output);
        completed(vision.backendName(), output);
    }

    private static void grabCut(VisionProcessor vision, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path output = requiredPath(options, "output");
        RgbaFrame image = ImageIoFrames.read(input);
        int x = intValue(options, "x", 0);
        int y = intValue(options, "y", 0);
        int width = intValue(options, "width", image.width() - x);
        int height = intValue(options, "height", image.height() - y);
        int iterations = intValue(options, "iterations", 5);
        AlphaMask mask = vision.grabCut(image, x, y, width, height, iterations);
        ImageIoFrames.write(maskFrame(mask), output);
        completed(vision.backendName(), output);
    }

    private static void interpolateImage(VisionProcessor vision, Map<String, String> options) throws Exception {
        Path previous = requiredPath(options, "previous");
        Path next = requiredPath(options, "next");
        Path output = requiredPath(options, "output");
        float position = floatValue(options, "position", 0.5f);
        RgbaFrame result = vision.interpolate(
                ImageIoFrames.read(previous),
                ImageIoFrames.read(next),
                position);
        ImageIoFrames.write(result, output);
        completed(vision.backendName(), output);
    }

    private static void video(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path output = requiredPath(options, "output");
        DecoderBackend decoder = enumValue(
                DecoderBackend.class,
                options.getOrDefault("decoder", "auto"));
        CompiledMediaPreset preset = loadPreset(processor, options);
        FrameTransform selected = preset == null ? transform(processor, options) : preset.transform();
        VideoEncodingOptions baseEncoding = preset == null
                ? VideoEncodingOptions.defaults()
                : preset.encodingOptions();
        VideoEncodingOptions encoding = encodingOptions(options, baseEncoding);
        int progressEvery = intValue(
                options,
                "progress-every",
                preset == null ? 30 : preset.progressEveryFrames());

        ProcessingProgressListener progress = value -> printProgress(value);
        var result = new FfmpegVideoProcessor(
                System.getProperty("chromellm.ffmpeg", "ffmpeg"),
                new FfmpegProbe(),
                decoder)
                .process(
                        input,
                        output,
                        processor,
                        selected,
                        encoding,
                        progress,
                        ProcessingControl.NEVER_CANCELLED,
                        progressEvery);

        System.out.printf(
                Locale.ROOT,
                "processed %d frames to %s using %s in %.3fs (%.3f fps, %.3fx realtime)%n",
                result.framesProcessed(),
                result.output(),
                result.processorBackend(),
                result.elapsed().toNanos() / 1_000_000_000.0,
                result.processingFramesPerSecond(),
                result.realtimeFactor());
    }

    private static void batchImage(ImageProcessor processor, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input-dir");
        Path output = requiredPath(options, "output-dir");
        boolean recursive = booleanValue(options, "recursive", true);
        boolean overwrite = booleanValue(options, "overwrite", false);
        int requestedParallelism = intValue(options, "parallelism", 1);
        CompiledMediaPreset preset = loadPreset(processor, options);
        int effectiveParallelism = processor.hardwareAccelerated() || preset != null ? 1 : requestedParallelism;
        String specification = preset == null ? pipelineSpecification(options) : null;
        TransformSpecParser parser = new TransformSpecParser(processor);

        var result = new BatchImageProcessor().process(
                input,
                output,
                recursive,
                overwrite,
                preset == null
                        ? () -> parser.parsePipeline(specification)
                        : preset::transform,
                effectiveParallelism);

        System.out.printf(
                Locale.ROOT,
                "batch discovered=%d succeeded=%d failed=%d backend=%s parallelism=%d elapsed=%.3fs%n",
                result.discovered(),
                result.succeeded(),
                result.failed(),
                processor.backendName(),
                effectiveParallelism,
                result.elapsed().toNanos() / 1_000_000_000.0);
        result.failures().forEach(failure ->
                System.err.println("FAILED " + failure.input() + " :: " + failure.message()));
    }

    private static void analyzeVideo(Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        DecoderBackend decoder = enumValue(
                DecoderBackend.class,
                options.getOrDefault("decoder", "auto"));
        double threshold = doubleValue(options, "cut-threshold", 0.35d);
        int sampleStride = intValue(options, "sample-stride", 4);
        int frameStride = intValue(options, "frame-stride", 1);

        var result = new VideoAnalyzer(
                System.getProperty("chromellm.ffmpeg", "ffmpeg"),
                new FfmpegProbe(),
                decoder)
                .analyze(input, new SceneCutDetector(threshold, sampleStride), frameStride);

        System.out.printf(
                Locale.ROOT,
                "frames=%d fps=%.6f size=%dx%d decoder=%s cuts=%d luma(avg/min/max)=%.6f/%.6f/%.6f elapsed=%.3fs%n",
                result.framesAnalyzed(),
                result.stream().framesPerSecond(),
                result.stream().width(),
                result.stream().height(),
                result.decoderBackend(),
                result.sceneCuts().size(),
                result.averageLuma(),
                result.minimumLuma(),
                result.maximumLuma(),
                result.elapsed().toNanos() / 1_000_000_000.0);
        result.sceneCuts().forEach(cut ->
                System.out.printf(Locale.ROOT, "cut frame=%d score=%.6f%n", cut.frameIndex(), cut.score()));
    }

    private static void interpolateVideo(VisionProcessor vision, Map<String, String> options) throws Exception {
        Path input = requiredPath(options, "input");
        Path output = requiredPath(options, "output");
        int factor = intValue(options, "factor", 2);
        DecoderBackend decoder = enumValue(
                DecoderBackend.class,
                options.getOrDefault("decoder", "auto"));

        var result = new FfmpegVideoInterpolator(
                System.getProperty("chromellm.ffmpeg", "ffmpeg"),
                new FfmpegProbe(),
                decoder)
                .interpolate(input, output, vision, factor);

        System.out.printf(
                Locale.ROOT,
                "generated %d frames to %s at %.3f fps using %s in %.3fs%n",
                result.framesProcessed(),
                result.output(),
                result.stream().framesPerSecond(),
                result.processorBackend(),
                result.elapsed().toNanos() / 1_000_000_000.0);
    }

    private static FrameTransform transform(ImageProcessor processor, Map<String, String> options) {
        return new TransformSpecParser(processor).parsePipeline(pipelineSpecification(options));
    }

    private static CompiledMediaPreset loadPreset(
            ImageProcessor processor,
            Map<String, String> options) throws Exception {
        String preset = options.get("preset");
        return preset == null || preset.isBlank()
                ? null
                : new MediaPresetLoader().load(Path.of(preset), processor);
    }

    private static VideoEncodingOptions encodingOptions(
            Map<String, String> options,
            VideoEncodingOptions base) {
        VideoCodec codec = options.containsKey("codec")
                ? enumValue(VideoCodec.class, options.get("codec"))
                : base.videoCodec();
        AudioMode audio = options.containsKey("audio")
                ? enumValue(AudioMode.class, options.get("audio"))
                : base.audioMode();
        return new VideoEncodingOptions(
                codec,
                options.getOrDefault("encoder-preset", base.preset()),
                intValue(options, "crf", base.crf()),
                options.getOrDefault("pixel-format", base.pixelFormat()),
                audio,
                intValue(options, "audio-bitrate", base.audioBitrateKbps()),
                intValue(options, "threads", base.threads()),
                base.extraArguments());
    }

    private static void printProgress(ProcessingProgress progress) {
        System.out.printf(
                Locale.ROOT,
                "progress frames=%d elapsed=%.3fs speed=%.3f fps%n",
                progress.framesProcessed(),
                progress.elapsed().toNanos() / 1_000_000_000.0,
                progress.processingFramesPerSecond());
    }

    private static String pipelineSpecification(Map<String, String> options) {
        String pipeline = options.get("pipeline");
        if (pipeline != null && !pipeline.isBlank()) return pipeline;
        String operation = required(options, "op");
        return operation
                + ":" + floatValue(options, "p0", 0f)
                + ":" + floatValue(options, "p1", 0f)
                + ":" + floatValue(options, "p2", 0f)
                + ":" + floatValue(options, "p3", 0f);
    }

    private static RgbaFrame maskFrame(AlphaMask mask) {
        byte[] alpha = mask.values();
        byte[] rgba = new byte[alpha.length * 4];
        for (int p = 0; p < alpha.length; p++) {
            int i = p * 4;
            rgba[i] = alpha[p];
            rgba[i + 1] = alpha[p];
            rgba[i + 2] = alpha[p];
            rgba[i + 3] = (byte)255;
        }
        return new RgbaFrame(mask.width(), mask.height(), rgba);
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
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("expected --option, got " + token);
            }
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
        return Enum.valueOf(
                type,
                value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    private static Path requiredPath(Map<String, String> options, String key) {
        return Path.of(required(options, key));
    }

    private static boolean booleanValue(Map<String, String> options, String key, boolean fallback) {
        return options.containsKey(key) ? Boolean.parseBoolean(options.get(key)) : fallback;
    }

    private static int intValue(Map<String, String> options, String key, int fallback) {
        return options.containsKey(key)
                ? Integer.parseInt(options.get(key))
                : fallback;
    }

    private static long longValue(Map<String, String> options, String key, long fallback) {
        return options.containsKey(key)
                ? Long.parseLong(options.get(key))
                : fallback;
    }

    private static float floatValue(Map<String, String> options, String key, float fallback) {
        return options.containsKey(key)
                ? Float.parseFloat(options.get(key))
                : fallback;
    }

    private static double doubleValue(Map<String, String> options, String key, double fallback) {
        return options.containsKey(key)
                ? Double.parseDouble(options.get(key))
                : fallback;
    }

    private static void completed(String backend, Path output) {
        System.out.println("output=" + output.toAbsolutePath());
        System.out.println("backend=" + backend);
    }

    private static void usage() {
        System.out.println("""
                ChromeLLM local image/video runtime

                Pixel/GPU:
                  info --backend auto|cpu|jna|jni
                  image --input in.png --output out.png --op grayscale [--backend auto]
                  image --input in.png --output out.png --pipeline "grayscale,unsharp:2:1.2:3"
                  image --input in.png --output out.png --preset grade.json
                  generate --output out.png --width 1024 --height 1024 --generator plasma --seed 42
                  place --input base.png --object object.png --x 100 --y 100 --output out.png
                  remove --input image.png --mask mask.png --radius 8 --passes 4 --output out.png
                  batch-image --input-dir photos --output-dir processed --pipeline "gamma:1.1,unsharp:2:1.0:3" --recursive true --parallelism 4
                  batch-image --input-dir photos --output-dir processed --preset grade.json
                  video --input in.mp4 --output out.mp4 --pipeline "temporal-denoise:0.25:0.35:4,unsharp:2:1.0:3,stabilize:8:4:0.75:0.35" --decoder auto
                  video --input in.mp4 --output out.mp4 --preset cinematic.json --codec h265 --crf 20 --audio aac --progress-every 30
                  analyze-video --input in.mp4 --cut-threshold 0.35 --sample-stride 4 --frame-stride 1 --decoder auto

                Pipeline syntax:
                  pixel-op[:p0:p1:p2:p3]
                  temporal-denoise[:currentWeight:cutThreshold:sampleStride]
                  stabilize[:searchRadius:sampleStride:smoothing:cutThreshold]
                  unsharp[:radius:amount:threshold]
                  region:x:y:width:height:pixel-op[:p0:p1:p2:p3]
                  range:startInclusive:endExclusive:pixel-op[:p0:p1:p2:p3]
                  flip-horizontal | flip-vertical | rotate-180

                OpenCV vision:
                  inpaint --input image.png --mask mask.png --radius 3 --output out.png --vision-backend auto|java|opencv
                  clone --input room.png --object chair.png --center-x 500 --center-y 400 --output out.png
                  grabcut --input image.png --x 100 --y 100 --width 500 --height 500 --output mask.png
                  interpolate-image --previous a.png --next b.png --position 0.5 --output middle.png
                  video-interpolate --input in.mp4 --output out.mp4 --factor 2 --decoder auto --vision-backend auto

                AUTO pixel backend prefers OpenCL through JNA, then JNI, then Java CPU.
                AUTO vision backend prefers OpenCV JNI, then Java fallback.
                AUTO decoder prefers direct FFmpeg JNI/libav, then the FFmpeg process/pipe fallback.
                GPU batch execution is serialized by default to avoid sharing one OpenCL kernel context concurrently.
                """);
    }
}
