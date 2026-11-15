// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\n"
                    + "llamaBin=bin/llama-cli\nmodel=models/model.gguf\n");
            int beforeInvalidTarget = requests.get();
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, root, Map.of(), provider));
            check(requests.get() == beforeInvalidTarget, "invalid target opened provider");
            SmartBuildMain.ProviderFactory relative = (options, env) -> {
                check(options.get("--llama-bin").equals(root.resolve("bin/llama-cli").toString()), "relative launcher root");
                check(options.get("--model").equals(root.resolve("models/model.gguf").toString()), "relative model root");
                return new ModelBackend() {
                    public String id() { return "llama.cpp-cli"; }
                    public ModelResponse infer(ModelRequest request) {
                        return new ModelResponse("relative-candidate", id(), hash(request.prompt()));
                    }
                };
            };
            SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), relative);
            Files.writeString(root.resolve("recipe.properties"), "promptFile=prompt.txt\nbackend=cli\n"
                    + "llamaBin=bin/llama-cli\nmodelUrl=https://example.invalid/model\n"
                    + "modelSha256=" + "0".repeat(64) + "\nmodelCacheDir=offline-cache\n");
            rejectIo(() -> SmartBuildMain.run(Path.of("recipe.properties"), root, target, Map.of(), provider, true));
            check(requests.get() == beforeInvalidTarget, "offline cache miss opened provider");
            nativePin(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("fork-native smart-build contract checks passed: " + checks);
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
