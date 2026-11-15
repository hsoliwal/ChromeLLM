// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline Maven smart-build recipe, replay, isolation and source-pin contracts. */
public final class SmartBuildContractTestMain {
    private static int checks;
    private SmartBuildContractTestMain() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("chromellm-smart-test-").toRealPath();
        Path root = Files.createTempDirectory("chromellm-smart-test-");
        try {
            Path target = Files.createDirectories(root.resolve("target"));
            Files.writeString(root.resolve("prompt.txt"), "मॉडल prompt", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("recipe.properties"), String.join("\n",
                    "promptFile=prompt.txt", "backend=cli", "llamaBin=/explicit/llama-cli",
                    "model=/explicit/model.gguf", "maxTokens=8", "timeoutSeconds=10") + "\n");
            AtomicInteger requests = new AtomicInteger();
            SmartBuildMain.ProviderFactory provider = (options, ignored) -> {
                check(options.get("--llama-bin").equals("/explicit/llama-cli"), "launcher selection lost");
                check(options.get("--model").equals("/explicit/model.gguf"), "model selection lost");
                return new ModelBackend() {
                    public String id() { return "llama.cpp-cli"; }
                    public ModelResponse infer(ModelRequest request) {
                        requests.incrementAndGet();
                        check(request.maxTokens() == 8, "max token policy lost");
                        check(request.timeout().toSeconds() == 10, "timeout policy lost");
                        return new ModelResponse("candidate", id(), hash(request.prompt()));
                    }
                };
            };
            Path output = SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider);
            check(Files.readString(output).equals("candidate"), "model candidate changed");
            check(output.startsWith(target.resolve("smart-build")), "write escaped target");
            Path repeated = SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider);
            check(output.equals(repeated) && requests.get() == 2, "idempotent replay contract failed");
            try (var entries = Files.list(target.resolve("smart-build"))) {
                check(entries.count() == 2, "output and receipt must be separate artifacts");
            }
            SmartBuildMain.ProviderFactory divergent = (options, env) -> new ModelBackend() {
                public String id() { return "llama.cpp-cli"; }
                public ModelResponse infer(ModelRequest request) {
                    return new ModelResponse("divergent", id(), hash(request.prompt()));
                }
            };
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), divergent));
            check(Files.readString(output).equals("candidate"), "conflict overwritten candidate");
            AtomicInteger invalidTargetOpens = new AtomicInteger();
            SmartBuildMain.ProviderFactory forbidden = (options, env) -> {
                invalidTargetOpens.incrementAndGet();
                throw new AssertionError("invalid output location admitted model preparation");
            };
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root,
                    root.resolve("not-target"), Map.of(), forbidden));
            check(invalidTargetOpens.get() == 0, "invalid target performed provider work");
            // A sparse prior artifact must be rejected without allocating its claimed size.
            long oversized = 256L * 1024 * 1024;
            try (FileChannel file = FileChannel.open(output, StandardOpenOption.WRITE)) {
                file.position(oversized);
                file.write(ByteBuffer.wrap(new byte[] {0}));
            }
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            check(Files.size(output) == oversized + 1, "oversized collision was overwritten");
            Files.writeString(root.resolve("recipe.properties"), "promptFile=../outside.txt\nbackend=cli\n");
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=gemini\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\nbackend=jni\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\nunknown=1\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\nmaxTokens=32769\n");
            rejectIllegal(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider));
            lazyModelOptions(root, target);
            lazyRecipe(root, target);
            nativePin(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("fork-native smart-build contract checks passed: " + checks);
    }

    private static void lazyModelOptions(Path root, Path target) throws Exception {
        Files.writeString(root.resolve("lazy.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=jni", "jniLib=/explicit/chromellm-jni",
                "modelUrl=https://github.com/hsoliwal/ChromeLLM/releases/download/pinned/weights.bin",
                "modelSha256=" + "a".repeat(64), "modelCacheDir=/explicit/cache",
                "computeBackend=cpu", "gpuLayers=0", "maxTokens=8", "timeoutSeconds=10") + "\n");
        AtomicInteger calls = new AtomicInteger();
        SmartBuildMain.ProviderFactory lazy = (options, environment) -> {
            check(options.get("--backend").equals("jni"), "JNI provider selection lost");
            check(options.get("--jni-lib").equals("/explicit/chromellm-jni"), "JNI library lost");
            check(options.get("--model-url").endsWith("/pinned/weights.bin"), "model URL lost");
            check(options.get("--model-sha256").equals("a".repeat(64)), "model digest lost");
            check(options.get("--model-cache-dir").equals("/explicit/cache"), "model cache lost");
            check(!options.containsKey("--model"), "lazy model replaced with a local path");
            check(options.get("--compute-backend").equals("cpu") && options.get("--gpu-layers").equals("0"),
                    "explicit CPU policy lost");
            return new ModelBackend() {
                public String id() { return "llama.cpp-jni"; }
                public ModelResponse infer(ModelRequest request) {
                    calls.incrementAndGet();
                    return new ModelResponse("lazy candidate", id(), hash(request.prompt()));
                }
            };
        };
        Path candidate = SmartBuildMain.run(Path.of("lazy.properties"), root, target, Map.of(), lazy);
        check(calls.get() == 1 && Files.readString(candidate).equals("lazy candidate"),
                "lazy model recipe was not executed");
    private static void lazyRecipe(Path root, Path target) throws Exception {
        Path cache = Files.createDirectory(root.resolve("model-cache"));
        String digest = "a".repeat(64);
        Files.writeString(root.resolve("lazy.properties"), String.join("\n",
                "promptFile=prompt.txt", "backend=jni", "jniLib=/explicit/missing-library",
                "modelUrl=https://example.invalid/licensed.gguf", "modelSha256=" + digest,
                "modelCacheDir=" + cache.toString().replace("\\", "\\\\"),
                "computeBackend=cpu", "gpuLayers=0") + "\n");
        SmartBuildMain.ProviderFactory selection = (options, environment) -> {
            check(options.get("--model-url").equals("https://example.invalid/licensed.gguf"),
                    "lazy model URL selection lost");
            check(options.get("--model-sha256").equals(digest), "lazy digest selection lost");
            check(Path.of(options.get("--model-cache-dir")).equals(cache), "lazy cache selection lost");
            check(!options.containsKey("--model"), "lazy model gained an implicit local path");
            try (ModelBackend deferred = StandaloneMain.backend(options, environment)) {
                check(deferred instanceof LazyModelBackend, "existing lazy provider was not selected");
                check(((LazyModelBackend) deferred).progress().state() == ModelArtifactCache.State.UNPREPARED,
                        "provider selection initialized a model eagerly");
            }
            try (var files = Files.list(cache)) {
                check(files.findAny().isEmpty(), "provider selection wrote model artifacts");
            }
            // This Java-only selection fixture does not pretend to execute a native model.
            return new ModelBackend() {
                public String id() { return "llama.cpp-jni"; }
                public ModelResponse infer(ModelRequest request) {
                    return new ModelResponse("lazy selection fixture", id(), hash(request.prompt()));
                }
            };
        };
        Path output = SmartBuildMain.run(Path.of("lazy.properties"), root, target, Map.of(), selection);
        check(Files.readString(output).equals("lazy selection fixture"), "lazy recipe result changed");
    }

    private static void nativePin(Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("fork"));
        Files.createDirectories(source.resolve("include"));
        Files.createDirectories(source.resolve("ggml/include"));
        Files.writeString(source.resolve("CMakeLists.txt"), "cmake_minimum_required(VERSION 3.20)\n");
        Files.writeString(source.resolve("include/llama.h"), "// stub\n");
        Files.writeString(source.resolve("ggml/include/ggml-backend.h"), "// stub\n");
        git(source, "init", "--quiet");
        git(source, "config", "user.name", "Fixture");
        git(source, "config", "user.email", "fixture@example.invalid");
        git(source, "add", "CMakeLists.txt", "include/llama.h", "ggml/include/ggml-backend.h");
        git(source, "commit", "--quiet", "-m", "fixture");
        String sha = git(source, "rev-parse", "HEAD").strip();
        ForkSourcePin.verify(source, sha);
        check(true, "verified source pin");
        rejectIo(() -> ForkSourcePin.verify(source, "0".repeat(40)));
        Files.writeString(source.resolve("include/llama.h"), "// changed\n");
        rejectIo(() -> ForkSourcePin.verify(source, sha));
        rejectIllegal(() -> ForkSourcePin.verify(source, "not-a-sha"));
        git(source, "restore", "include/llama.h");
        ForkSourcePin.verify(source, sha);
        Files.writeString(source.resolve("untracked.c"), "// untracked\n");
        rejectIo(() -> ForkSourcePin.verify(source, sha));
    }
    private static String git(Path root, String... args) throws Exception {
        java.util.ArrayList<String> commands = new java.util.ArrayList<>(java.util.List.of("git", "-C", root.toString()));
        commands.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(commands).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new AssertionError("fixture git failed: " + new String(output));
        return new String(output, StandardCharsets.UTF_8);
    }
    private static String hash(String prompt) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(prompt.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void rejectIo(Checked step) throws Exception {
        try { step.run(); throw new AssertionError("expected IO failure"); }
        catch (IOException expected) { checks++; }
    }
    private static void rejectIllegal(Checked step) throws Exception {
        try { step.run(); throw new AssertionError("expected configuration rejection"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean accepted, String message) {
        checks++;
        if (!accepted) throw new AssertionError(message);
    }
}
