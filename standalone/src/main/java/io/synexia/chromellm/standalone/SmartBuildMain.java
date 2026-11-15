// SPDX-License-Identifier: MIT
// Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Explicit Maven smart-build recipe: inference candidate only; never edits source files. */
public final class SmartBuildMain {
    private static final int MAX_RECIPE_BYTES = 64 * 1024;
    private static final Set<String> KEYS = Set.of("promptFile", "backend", "llamaBin", "jniLib",
            "model", "modelUrl", "modelSha256", "modelCacheDir", "contextTokens", "threads",
            "gpuLayers", "computeBackend", "deviceBackend", "device", "maxTokens", "timeoutSeconds");

    @FunctionalInterface
    interface ProviderFactory {
        ModelBackend open(Map<String, String> options, Map<String, String> environment) throws Exception;
    }

    private SmartBuildMain() { }

    public static void main(String[] args) throws Exception {
        run(args, System.getenv(), StandaloneMain::backend);
    }

    static Path run(String[] args, Map<String, String> environment, ProviderFactory providerFactory) throws Exception {
        if ((args.length != 3 && args.length != 4) || args[0].isBlank()) {
            throw new IllegalArgumentException("expected recipe, standalone root, Maven target directory, and optional offline flag");
        }
        String offline = args.length == 4 ? args[3] : Boolean.toString(
                "offline=true".equals(System.getProperty("chromellm.offline"))
                        || Boolean.getBoolean("chromellm.offline"));
        if (offline.startsWith("--offline=")) offline = offline.substring("--offline=".length());
        if (!offline.equals("true") && !offline.equals("false")) {
            throw new IllegalArgumentException("offline flag requires true or false");
        }
        return run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), environment, providerFactory,
                offline.equals("true"));
    }

    static Path run(Path recipe, Path workspace, Path buildDirectory,
                    Map<String, String> environment, ProviderFactory providerFactory) throws Exception {
        return run(recipe, workspace, buildDirectory, environment, providerFactory, false);
    }

    static Path run(Path recipe, Path workspace, Path buildDirectory,
                    Map<String, String> environment, ProviderFactory providerFactory, boolean offline) throws Exception {
        Path root = workspace.toRealPath();
        // Reject the destination before any artifact acquisition or provider initialization.
        Path outputRoot = validateOutputRoot(root, buildDirectory);
        NativeBuildArtifacts.buildDirectory(root, outputRoot.getParent());
        Path recipePath = confined(root, recipe, "recipe");
        byte[] recipeBytes;
        try (InputStream input = Files.newInputStream(recipePath)) {
            recipeBytes = input.readNBytes(MAX_RECIPE_BYTES + 1);
        }
        if (recipeBytes.length == 0 || recipeBytes.length > MAX_RECIPE_BYTES) {
            throw new IOException("smart-build recipe is empty or exceeds 64 KiB");
        }
        String encodedRecipe = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(recipeBytes)).toString();
        Properties config = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("duplicate smart-build recipe key: " + key);
                return super.put(key, value);
            }
        };
        config.load(new StringReader(encodedRecipe));
        for (String key : config.stringPropertyNames()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown smart-build recipe option: " + key);
        }
        Path promptFile = confined(root, Path.of(required(config, "promptFile")), "promptFile");
        String backend = required(config, "backend");
        if (!backend.equals("cli") && !backend.equals("jni")) {
            throw new IllegalArgumentException("smart-build requires explicit fork-backed cli or jni provider");
        }
        String prompt = StandaloneMain.readPrompt(promptFile, false);
        int maxTokens = boundedInteger(config, "maxTokens", 512, 1, 32768);
        int timeoutSeconds = boundedInteger(config, "timeoutSeconds", 120, 1, 3600);
        ModelRequest request = new ModelRequest(prompt, maxTokens, Duration.ofSeconds(timeoutSeconds));
        byte[] promptBytes = StdioProtocol.encode(prompt, StdioProtocol.MAX_PROMPT_BYTES);
        byte[] inputHash = sha256(promptBytes);
        byte[] recipeHash = sha256(recipeBytes);
        byte[] combined = new byte[inputHash.length + recipeHash.length];
        System.arraycopy(inputHash, 0, combined, 0, inputHash.length);
        System.arraycopy(recipeHash, 0, combined, inputHash.length, recipeHash.length);
        String runKey = hex(sha256(combined));

        Map<String, String> options = new HashMap<>();
        options.put("--backend", backend);
        for (Map.Entry<String, String> entry : Map.ofEntries(
                Map.entry("llamaBin", "--llama-bin"), Map.entry("jniLib", "--jni-lib"),
                Map.entry("model", "--model"), Map.entry("modelUrl", "--model-url"),
                Map.entry("modelSha256", "--model-sha256"), Map.entry("modelCacheDir", "--model-cache-dir"),
                Map.entry("contextTokens", "--context-tokens"), Map.entry("threads", "--threads"),
                Map.entry("gpuLayers", "--gpu-layers"), Map.entry("computeBackend", "--compute-backend"),
                Map.entry("deviceBackend", "--device-backend"), Map.entry("device", "--device"))
                .entrySet()) {
            String value = config.getProperty(entry.getKey());
            if (value != null && !value.isBlank()) options.put(entry.getValue(), value);
        }
        // Recipe paths resolve against the standalone project, independent of Maven's launch directory.
        for (String key : java.util.List.of("--llama-bin", "--jni-lib", "--model", "--model-cache-dir")) {
            String value = options.get(key);
            if (value != null) options.put(key, root.resolve(value).normalize().toString());
        }
        if (offline && options.containsKey("--model-url")) {
            // Reuse the engine's lazy-provider admission before changing its model options.
            // Construction and close do not infer, open the provider, or acquire the model.
            StandaloneMain.backend(Map.copyOf(options), environment).close();
            Path model = new ModelArtifactCache(options.get("--model-url"), options.get("--model-sha256"),
                    Path.of(options.get("--model-cache-dir")), ModelArtifactCache.MAX_BYTES, true).resolve();
            options.remove("--model-url");
            options.remove("--model-sha256");
            options.remove("--model-cache-dir");
            options.put("--model", model.toString());
        }
        // The selected provider and all options are admitted by the pre-existing engine contract.
        byte[] candidate;
        String provider;
        try (ModelBackend model = providerFactory.open(Map.copyOf(options), environment)) {
            ModelResponse result = model.infer(request);
            if (!model.id().equals(result.backendId())
                    || !MessageDigest.isEqual(inputHash, HexFormat.of().parseHex(result.promptSha256()))) {
                throw new IOException("smart-build model returned a mismatched backend or prompt receipt");
            }
            provider = model.id();
            candidate = StdioProtocol.encode(result.text(), StdioProtocol.MAX_RESPONSE_BYTES);
        }
        Files.createDirectories(outputRoot);
        validateOutputRoot(root, buildDirectory);
        Path output = outputRoot.resolve("candidate-" + runKey + ".txt");
        publish(output, candidate);
        String receipt = "recipe.sha256=" + hex(recipeHash) + "\n"
                + "prompt.sha256=" + hex(inputHash) + "\n"
                + "candidate.sha256=" + hex(sha256(candidate)) + "\n"
                + "backend=" + provider + "\n";
        publish(outputRoot.resolve("receipt-" + runKey + ".properties"),
                receipt.getBytes(StandardCharsets.UTF_8));
        System.out.println("ChromeLLM smart-build candidate SHA-256: " + hex(sha256(candidate)));
        return output;
    }

    private static Path validateOutputRoot(Path root, Path buildDirectory) throws IOException {
        Path suppliedTarget = buildDirectory.toAbsolutePath().normalize();
        Path target = root.resolve("target");
        // Resolve ancestor aliases consistently with workspace.toRealPath(), while
        // keeping the final target directory itself subject to the no-link check.
        if (suppliedTarget.getParent() == null
                || !suppliedTarget.getParent().toRealPath().resolve(suppliedTarget.getFileName()).equals(target)
                || !Files.isDirectory(suppliedTarget, LinkOption.NOFOLLOW_LINKS)
                || !suppliedTarget.toRealPath().equals(target)) {
            throw new IOException("smart-build requires the existing in-project Maven target directory");
        }
        Path outputRoot = target.resolve("smart-build");
        if (Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)
                || !outputRoot.toRealPath().equals(outputRoot))) {
            throw new IOException("smart-build outputs must stay inside the project build directory");
        }
        return outputRoot;
    }

    private static Path confined(Path root, Path candidate, String field) throws IOException {
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                || !resolved.toRealPath().startsWith(root)) {
            throw new IOException(field + " must name an existing regular file inside the project");
        }
        return resolved;
    }

    private static void publish(Path path, byte[] content) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!sameContent(path, content)) {
                throw new IOException("smart-build artifact collision; source and prior result remain unchanged");
            }
            return;
        }
        Path staged = Files.createTempFile(path.getParent(), ".chromellm-smart-", ".tmp");
        try {
            Files.write(staged, content, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.createLink(path, staged); }
            catch (FileAlreadyExistsException concurrent) {
                if (!sameContent(path, content)) {
                    throw new IOException("concurrent smart-build artifact conflict", concurrent);
                }
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static boolean sameContent(Path path, byte[] content) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != content.length) {
            return false;
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return Arrays.equals(input.readNBytes(content.length + 1), content);
        }
    }

    private static int boundedInteger(Properties options, String key, int fallback, int min, int max) {
        String raw = options.getProperty(key);
        int value = raw == null ? fallback : Integer.parseInt(raw);
        if (value < min || value > max) throw new IllegalArgumentException("invalid " + key);
        return value;
    }
    private static String required(Properties options, String key) {
        String value = options.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
