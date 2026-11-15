// SPDX-License-Identifier: MIT
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
        if (args.length != 3 || args[0].isBlank()) {
            throw new IllegalArgumentException("expected recipe, standalone root, and Maven target directory");
        }
        run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), System.getenv(), StandaloneMain::backend);
    }

    static Path run(Path recipe, Path workspace, Path buildDirectory,
                    Map<String, String> environment, ProviderFactory providerFactory) throws Exception {
        Path root = workspace.toRealPath();
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
        Properties config = new Properties();
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
        Path target = buildDirectory.toAbsolutePath().normalize();
        if (!target.equals(root.resolve("target"))
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                || !target.toRealPath().equals(target)) {
            throw new IOException("smart-build requires the existing in-project Maven target directory");
        }
        Path outputRoot = target.resolve("smart-build");
        Files.createDirectories(outputRoot);
        if (!outputRoot.toRealPath().startsWith(root) || Files.isSymbolicLink(outputRoot)) {
            throw new IOException("smart-build outputs must stay inside the project build directory");
        }
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
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(Files.readAllBytes(path), content)) {
                throw new IOException("smart-build artifact collision; source and prior result remain unchanged");
            }
            return;
        }
        Path staged = Files.createTempFile(path.getParent(), ".chromellm-smart-", ".tmp");
        try {
            Files.write(staged, content, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.createLink(path, staged); }
            catch (FileAlreadyExistsException concurrent) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !Arrays.equals(Files.readAllBytes(path), content)) {
                    throw new IOException("concurrent smart-build artifact conflict", concurrent);
                }
            }
        } finally {
            Files.deleteIfExists(staged);
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
