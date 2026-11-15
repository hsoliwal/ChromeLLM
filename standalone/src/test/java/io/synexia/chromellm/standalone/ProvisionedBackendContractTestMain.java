// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Native/model acquisition contracts; fixture bytes are never loaded as an executable or a model. */
public final class ProvisionedBackendContractTestMain {
    private static final byte[] MODEL = "synthetic model artifact".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NATIVE = "synthetic native artifact".getBytes(StandardCharsets.UTF_8);
    private static final ModelRequest REQUEST = new ModelRequest("bounded prompt", 8, Duration.ofSeconds(5));
    private static int checks;

    private ProvisionedBackendContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("chromellm-provisioning-").toRealPath();
        try {
            factoryAdmission(root);
            cachePathAdmission(root);
            pairedArtifacts(root);
            concurrentAcquisition(root);
            closeDuringSecondArtifact(root);
            smartBuildMapping(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("provisioned backend contract checks passed: " + checks);
    }

    private static void factoryAdmission(Path root) throws Exception {
        Path cache = root.resolve("factory-cache");
        Path model = Files.write(root.resolve("local-model.gguf"), MODEL);
        List<String> nativeOptions = new ArrayList<>(List.of("--backend", "jni", "--model", model.toString(),
                "--jni-url", "https://example.invalid/pinned/native", "--jni-sha256", hash(NATIVE),
                "--jni-bytes", Integer.toString(NATIVE.length), "--jni-cache-dir", cache.toString(),
                "--compute-backend", "cpu", "--gpu-layers", "0"));
        try (ModelBackend deferred = StandaloneMain.openBackend(nativeOptions.toArray(String[]::new))) {
            check(deferred.id().equals("llama.cpp-jni"), "factory selected the wrong provider");
            check(!Files.exists(cache), "factory performed acquisition or created a cache eagerly");
        }
        check(!Files.exists(cache), "closing an unprepared provider changed the cache");
        try (ModelBackend offline = StandaloneMain.openBackend(append(nativeOptions, "--offline", "true"))) {
            expectIo(() -> offline.infer(REQUEST));
        }
        check(!Files.exists(cache), "offline miss created a cache directory");
        for (String[] invalid : List.of(
                append(nativeOptions, "--jni-lib", "/explicit/lib"),
                replace(nativeOptions, "--jni-bytes", "0"),
                replace(nativeOptions, "--jni-sha256", "bad"),
                replace(nativeOptions, "--jni-url", "http://example.invalid/native"),
                replace(nativeOptions, "--backend", "cli"),
                replace(nativeOptions, "--gpu-layers", "1"),
                replace(nativeOptions, "--compute-backend", "unknown"),
                append(nativeOptions, "--threads", "0"),
                append(nativeOptions, "--context-tokens", "1"),
                append(nativeOptions, "--offline", "sometimes"),
                append(nativeOptions, "--agent", "true"),
                append(nativeOptions, "--workspace", root.toString()),
                append(nativeOptions, "--agent-endpoint", "http://127.0.0.1:32123"),
                append(nativeOptions, "--model-bytes", "12"),
                append(nativeOptions, "--device", "\u0000"))) {
            expectIllegal(() -> StandaloneMain.openBackend(invalid));
            check(!Files.exists(cache), "invalid options touched the artifact cache");
        }
        List<String> incomplete = new ArrayList<>(nativeOptions);
        int sizeOption = incomplete.indexOf("--jni-bytes");
        incomplete.subList(sizeOption, sizeOption + 2).clear();
        expectIllegal(() -> StandaloneMain.openBackend(incomplete.toArray(String[]::new)));
        List<String> both = new ArrayList<>(nativeOptions);
        int modelOption = both.indexOf("--model");
        both.subList(modelOption, modelOption + 2).clear();
        both.addAll(List.of("--model-url", "https://example.invalid/pinned/model", "--model-sha256", hash(MODEL),
                "--model-bytes", Integer.toString(MODEL.length), "--model-cache-dir", cache.toString()));
        try (ModelBackend deferred = StandaloneMain.openBackend(both.toArray(String[]::new))) {
            check(deferred instanceof LazyModelBackend, "paired artifact provider was not lazy");
            check(((LazyModelBackend) deferred).progress().state() == ModelArtifactCache.State.UNPREPARED,
                    "paired provider was initialized at construction");
        }
        check(!Files.exists(cache), "paired construction performed I/O");
        expectIllegal(() -> StandaloneMain.openBackend("--backend", "cli", "--llama-bin", "/explicit/llama",
                "--model-url", "https://example.invalid/model", "--model-sha256", hash(MODEL),
                "--model-cache-dir", cache.toString(), "--device", "invalid device name"));
        check(!Files.exists(cache), "invalid CLI selector performed acquisition");
    }

    private static void cachePathAdmission(Path root) throws Exception {
        Path real = Files.createDirectory(root.resolve("artifact-real"));
        Path cache = Files.createDirectory(real.resolve("cache"));
        final Path alias;
        try {
            alias = Files.createSymbolicLink(root.resolve("artifact-alias"), real);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            System.out.println("artifact cache symbolic-link fixtures unavailable: " + unavailable);
            return;
        }
        for (String suffix : List.of(".gguf", ".bin")) {
            byte[] bytes = suffix.equals(".gguf") ? MODEL : NATIVE;
            Path cached = Files.write(cache.resolve(hash(bytes) + suffix), bytes);
            ModelArtifactCache artifact = ModelArtifactCache.artifact("https://example.invalid/pinned/artifact",
                    hash(bytes), alias.resolve("cache"), bytes.length, suffix);
            expectIo(artifact::resolve);
            expectIo(() -> artifact.resolveWithOfflinePolicy(true));
            check(java.util.Arrays.equals(Files.readAllBytes(cached), bytes),
                    "cache alias refusal altered a verified artifact");
        }
    }

    private static void pairedArtifacts(Path root) throws Exception {
        Path cache = Files.createDirectory(root.resolve("paired"));
        AtomicInteger modelFetches = new AtomicInteger();
        AtomicInteger nativeFetches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ModelArtifactCache[] artifacts = artifacts(cache, modelFetches, nativeFetches);
        try (LazyModelBackend backend = new LazyModelBackend("llama.cpp-jni", artifacts, false,
                files -> provider(files, opens, closes))) {
            check(modelFetches.get() == 0 && nativeFetches.get() == 0 && opens.get() == 0,
                    "paired provider prepared eagerly");
            check(backend.infer(REQUEST).text().equals("fixture"), "first paired inference failed");
            backend.infer(REQUEST);
            check(modelFetches.get() == 1 && nativeFetches.get() == 1 && opens.get() == 1,
                    "warm inference repeated acquisition or provider creation");
            check(backend.progress().state() == ModelArtifactCache.State.READY
                    && backend.progress().bytes() == MODEL.length + NATIVE.length, "combined progress is wrong");
        }
        check(closes.get() == 1, "loaded provider was not closed exactly once");
        try (LazyModelBackend offline = new LazyModelBackend("llama.cpp-jni",
                artifacts(cache, modelFetches, nativeFetches), true, files -> provider(files, opens, closes))) {
            offline.infer(REQUEST);
        }
        check(modelFetches.get() == 1 && nativeFetches.get() == 1 && opens.get() == 2,
                "offline reuse fetched an artifact or omitted inference");
        Path nativeFile = cache.resolve(hash(NATIVE) + ".bin");
        Files.write(nativeFile, new byte[NATIVE.length]);
        try (LazyModelBackend corrupt = new LazyModelBackend("llama.cpp-jni",
                artifacts(cache, modelFetches, nativeFetches), true, files -> provider(files, opens, closes))) {
            expectIo(() -> corrupt.infer(REQUEST));
        }
        check(opens.get() == 2 && nativeFetches.get() == 1, "corrupt native cache was loaded or repaired");
        Files.delete(nativeFile);
        try (LazyModelBackend missing = new LazyModelBackend("llama.cpp-jni",
                artifacts(cache, modelFetches, nativeFetches), true, files -> provider(files, opens, closes))) {
            expectIo(() -> missing.infer(REQUEST));
        }
        check(opens.get() == 2 && nativeFetches.get() == 1, "offline native miss performed work");
        ModelArtifactCache badNative = new ModelArtifactCache("https://example.invalid/native", "0".repeat(64),
                cache, ignored -> new ByteArrayInputStream(NATIVE), NATIVE.length, NATIVE.length, ".bin");
        try (LazyModelBackend mismatch = new LazyModelBackend("llama.cpp-jni",
                new ModelArtifactCache[] {artifacts[0], badNative}, false, files -> provider(files, opens, closes))) {
            expectIo(() -> mismatch.infer(REQUEST));
        }
        try (var paths = Files.list(cache)) {
            check(paths.noneMatch(path -> path.toString().endsWith(".part")), "failed native transfer leaked staging");
        }
        check(Files.readAllBytes(cache.resolve(hash(MODEL) + ".gguf")).length == MODEL.length,
                "failed native acquisition damaged the model cache");
    }

    private static void concurrentAcquisition(Path root) throws Exception {
        Path cache = Files.createDirectory(root.resolve("concurrent"));
        AtomicInteger modelFetches = new AtomicInteger();
        AtomicInteger nativeFetches = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) futures.add(workers.submit(() -> {
                try (LazyModelBackend backend = new LazyModelBackend("llama.cpp-jni",
                        artifacts(cache, modelFetches, nativeFetches), false,
                        files -> provider(files, opens, closes))) {
                    return backend.infer(REQUEST).text();
                }
            }));
            for (var result : futures) check(result.get(5, TimeUnit.SECONDS).equals("fixture"), "concurrent result changed");
        }
        check(modelFetches.get() == 1 && nativeFetches.get() == 1, "concurrent callers duplicated a download");
        check(opens.get() == 8 && closes.get() == 8, "concurrent providers lost lifecycle ownership");
    }

    private static void closeDuringSecondArtifact(Path root) throws Exception {
        Path cache = Files.createDirectory(root.resolve("close-second"));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        InputStream stalled = new InputStream() {
            private boolean stopped;
            @Override public synchronized int read() throws IOException {
                reading.countDown();
                while (!stopped) {
                    try { wait(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("fixture interrupted", interrupted);
                    }
                }
                throw new IOException("fixture closed");
            }
            @Override public synchronized void close() { stopped = true; closed.countDown(); notifyAll(); }
        };
        ModelArtifactCache model = new ModelArtifactCache("https://example.invalid/model", hash(MODEL), cache,
                ignored -> new ByteArrayInputStream(MODEL), MODEL.length);
        ModelArtifactCache bridge = new ModelArtifactCache("https://example.invalid/native", hash(NATIVE), cache,
                ignored -> stalled, NATIVE.length, NATIVE.length, ".bin");
        AtomicInteger opens = new AtomicInteger();
        LazyModelBackend backend = new LazyModelBackend("llama.cpp-jni", new ModelArtifactCache[] {model, bridge},
                false, files -> { opens.incrementAndGet(); throw new AssertionError("closed provider opened"); });
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = worker.submit(() -> backend.infer(REQUEST));
            check(reading.await(5, TimeUnit.SECONDS), "secondary download never started");
            backend.close();
            try { future.get(5, TimeUnit.SECONDS); throw new AssertionError("closed acquisition succeeded"); }
            catch (ExecutionException expected) {
                check(expected.getCause() instanceof InterruptedException || expected.getCause() instanceof IOException,
                        "close lost acquisition failure type");
            }
            check(closed.await(5, TimeUnit.SECONDS) && opens.get() == 0, "secondary acquisition was not cancelled");
        } finally {
            backend.close();
        }
    }

    private static void smartBuildMapping(Path root) throws Exception {
        Path project = Files.createDirectory(root.resolve("smart"));
        Path target = Files.createDirectory(project.resolve("target"));
        Path cache = project.resolve("cache");
        Files.writeString(project.resolve("prompt.txt"), REQUEST.prompt());
        Files.writeString(project.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=jni\n"
                + "jniUrl=https://example.invalid/native\njniSha256=" + hash(NATIVE) + "\njniBytes=" + NATIVE.length
                + "\njniCacheDir=cache\nmodelUrl=https://example.invalid/model\nmodelSha256=" + hash(MODEL)
                + "\nmodelBytes=" + MODEL.length + "\nmodelCacheDir=cache\ncomputeBackend=cpu\noffline=false\n");
        AtomicInteger opens = new AtomicInteger();
        SmartBuildMain.ProviderFactory online = (options, environment) -> {
            check(options.get("--jni-url").equals("https://example.invalid/native"), "native URL was lost");
            check(options.get("--jni-bytes").equals(Integer.toString(NATIVE.length)), "native length was lost");
            check(options.get("--model-bytes").equals(Integer.toString(MODEL.length)), "model length was lost");
            check(Path.of(options.get("--jni-cache-dir")).equals(cache), "native cache path was not resolved");
            check(options.get("--offline").equals("false") && !Files.exists(cache), "online recipe prepared eagerly");
            return reply(opens);
        };
        SmartBuildMain.run(Path.of("recipe.properties"), project, target, Map.of(), online);
        Files.createDirectory(cache);
        Files.write(cache.resolve(hash(MODEL) + ".gguf"), MODEL);
        Path nativeFile = Files.write(cache.resolve(hash(NATIVE) + ".bin"), NATIVE);
        SmartBuildMain.ProviderFactory offline = (options, environment) -> {
            check(options.get("--offline").equals("true"), "Maven offline was overridden by the recipe");
            check(!options.containsKey("--jni-url") && !options.containsKey("--model-url"), "offline preflight did not resolve caches");
            check(Path.of(options.get("--jni-lib")).equals(nativeFile), "offline native identity changed");
            return reply(opens);
        };
        SmartBuildMain.run(Path.of("recipe.properties"), project, target, Map.of(), offline, true);
        int opened = opens.get();
        Files.delete(nativeFile);
        expectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), project, target, Map.of(), offline, true));
        check(opens.get() == opened, "offline missing native invoked the provider");
    }

    private static ModelArtifactCache[] artifacts(Path cache, AtomicInteger modelFetches, AtomicInteger nativeFetches) {
        return new ModelArtifactCache[] {
                new ModelArtifactCache("https://example.invalid/model", hash(MODEL), cache, ignored -> {
                    modelFetches.incrementAndGet(); return new ByteArrayInputStream(MODEL);
                }, MODEL.length, MODEL.length, ".gguf"),
                new ModelArtifactCache("https://example.invalid/native", hash(NATIVE), cache, ignored -> {
                    nativeFetches.incrementAndGet(); return new ByteArrayInputStream(NATIVE);
                }, NATIVE.length, NATIVE.length, ".bin")};
    }

    private static ModelBackend provider(Path[] files, AtomicInteger opens, AtomicInteger closes) throws IOException {
        if (files.length != 2 || !java.util.Arrays.equals(Files.readAllBytes(files[0]), MODEL)
                || !java.util.Arrays.equals(Files.readAllBytes(files[1]), NATIVE)) {
            throw new AssertionError("provider opened before both exact artifacts were verified");
        }
        opens.incrementAndGet();
        return new ModelBackend() {
            public String id() { return "llama.cpp-jni"; }
            public ModelResponse infer(ModelRequest request) { return new ModelResponse("fixture", id(), hash(request.prompt().getBytes(StandardCharsets.UTF_8))); }
            public void close() { closes.incrementAndGet(); }
        };
    }

    private static ModelBackend reply(AtomicInteger opens) {
        opens.incrementAndGet();
        return new ModelBackend() {
            public String id() { return "llama.cpp-jni"; }
            public ModelResponse infer(ModelRequest request) { return new ModelResponse("fixture", id(), hash(request.prompt().getBytes(StandardCharsets.UTF_8))); }
        };
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String[] append(List<String> original, String key, String value) {
        List<String> args = new ArrayList<>(original); args.add(key); args.add(value); return args.toArray(String[]::new);
    }
    private static String[] replace(List<String> original, String key, String value) {
        List<String> args = new ArrayList<>(original); args.set(args.indexOf(key) + 1, value); return args.toArray(String[]::new);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expectIo(Checked step) throws Exception {
        try { step.run(); throw new AssertionError("expected IOException"); }
        catch (IOException expected) { checks++; }
    }
    private static void expectIllegal(Checked step) throws Exception {
        try { step.run(); throw new AssertionError("expected invalid configuration"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean accepted, String message) {
        if (!accepted) throw new AssertionError(message);
        checks++;
    }
}
