// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Explicit real-model CPU gate; never included in the ordinary offline verify suite.
 * Reuses the tree/river, deterministic repeat, receipt and close checks from the
 * historical RealGgufContractTestMain at ChromeLLM commit
 * 97f4b7de4a5d1d75b781b294840dc418ccd67650 with the current JNI backend.
 * The pinned SmolLM2-135M Q4_K_M artifact is unchanged GGUF v3 despite its .bin name:
 * https://github.com/hsoliwal/ChromeLLM/releases/tag/model-smollm2-135m-q4km-8030f045
 * License: https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/LICENSE
 * Notice: https://github.com/hsoliwal/ChromeLLM/releases/download/model-smollm2-135m-q4km-8030f045/NOTICE.txt
 * The test never executes model output or asserts factual or coding accuracy.
 */
public final class RealGgufContractTestMain {
    private static final byte[] GGUF_MAGIC = {'G', 'G', 'U', 'F'};
    private static final String PROMPT = "<|im_start|>user\n"
            + "Write one short sentence about an oak tree.<|im_end|>\n"
            + "<|im_start|>assistant\n";
    private static final String OTHER_PROMPT = "<|im_start|>user\n"
            + "Write one short sentence about a river.<|im_end|>\n"
            + "<|im_start|>assistant\n";
    private static final String MODEL_SHA256 =
            "8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684";
    private static final long MODEL_BYTES = 105_454_144L;
    private static final String UNREACHABLE_URL = "https://example.invalid/unavailable-model.gguf";
    private static final String TREE_PROMPT = "<|im_start|>user\n"
            + "Write one short sentence about an oak tree.<|im_end|>\n<|im_start|>assistant\n";
    private static final String RIVER_PROMPT = "<|im_start|>user\n"
            + "Write one short sentence about a river.<|im_end|>\n<|im_start|>assistant\n";
    private static int checks;

    private RealGgufContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 5) {
            smartBuild(args);
            return;
        }
        if (args.length != 6) {
            throw new IllegalArgumentException("expected JNI library, model HTTPS URL, SHA-256, "
                    + "cache directory, exact model byte length and offline flag");
        }
        check(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xcheck:jni"),
                "the real-model JVM must enable -Xcheck:jni");
        Path library = Path.of(args[0]).toAbsolutePath().normalize();
        check(Files.isRegularFile(library) && Files.isReadable(library),
                "the supplied JNI library must exist and be readable");
        long expectedBytes = Long.parseLong(args[4]);
        if (expectedBytes < GGUF_MAGIC.length || expectedBytes > ModelArtifactCache.MAX_BYTES) {
            throw new IllegalArgumentException("invalid exact model byte length");
        }
        String offlineArgument = args[5];
        if (offlineArgument.startsWith("--offline=")) {
            offlineArgument = offlineArgument.substring("--offline=".length());
        }
        if (!offlineArgument.equals("true") && !offlineArgument.equals("false")) {
            throw new IllegalArgumentException("offline flag must be true or false");
        }
        boolean offline = Boolean.parseBoolean(offlineArgument);
        Path directory = Path.of(args[3]).toAbsolutePath().normalize();
        // The Maven profile explicitly selects this directory. Ordinary Java builds never run here.
        ModelArtifactCache cache = new ModelArtifactCache(args[1], args[2], directory, expectedBytes, offline);
        Files.createDirectories(directory);
        boolean cached = Files.exists(directory.resolve(args[2].toLowerCase(Locale.ROOT) + ".gguf"),
                LinkOption.NOFOLLOW_LINKS);
        Path model = cache.resolve();
        check(Files.size(model) == expectedBytes, "exact model byte length");
        try (InputStream input = Files.newInputStream(model, LinkOption.NOFOLLOW_LINKS)) {
            check(Arrays.equals(input.readNBytes(GGUF_MAGIC.length), GGUF_MAGIC), "GGUF file signature");
        }
        System.out.println("Model artifact: " + (cached ? "verified cache hit" : "downloaded and verified")
                + "; SHA-256=" + args[2].toLowerCase(Locale.ROOT) + "; bytes=" + expectedBytes);
        check(cache.resolve().equals(model), "verified model cache reuse");

        ModelRequest request = new ModelRequest(PROMPT, 48, Duration.ofSeconds(120));
        String expectedPromptHash = hash(PROMPT);
        // CPU is explicit: no hosted backend, synthetic provider, or GPU fallback is selected here.
        LlamaCppJniBackend backend = new LlamaCppJniBackend(library, model, 512, 2, 0, "cpu");
        try {
            check(backend.id().equals("llama.cpp-jni"), "native provider identity");
            ModelResponse first = backend.infer(request);
            checkResponse(first, backend.id(), expectedPromptHash);

            ModelResponse repeated = backend.infer(request);
            checkResponse(repeated, backend.id(), expectedPromptHash);
            check(repeated.text().equals(first.text()), "greedy CPU inference repeatability");

            ModelRequest otherRequest = new ModelRequest(OTHER_PROMPT, 48, Duration.ofSeconds(120));
            ModelResponse other = backend.infer(otherRequest);
            checkResponse(other, backend.id(), hash(OTHER_PROMPT));
            check(!other.promptSha256().equals(expectedPromptHash), "different prompt identity");

            backend.close();
            backend.close();
            boolean closed = false;
            try {
                backend.infer(request);
            } catch (IOException expected) {
                closed = true;
            }
            check(closed, "inference after native model close must be refused");
        } finally {
            backend.close();
        }
        System.out.println("PASS: licensed GGUF CPU inference, deterministic repeat, receipts and close");
    }

    private static void checkResponse(ModelResponse response, String backend, String promptHash) {
        check(response.backendId().equals(backend), "response provider identity");
        check(response.promptSha256().equals(promptHash), "prompt receipt SHA-256");
        check(!response.text().isBlank(), "real GGUF inference must produce nonblank text");
        check(response.text().getBytes(StandardCharsets.UTF_8).length <= StdioProtocol.MAX_RESPONSE_BYTES,
                "bounded native output");
    }

    private static void smartBuild(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "usage: RealGgufContractTestMain <jni-library> <cache-dir> <work-dir> <model-url> <sha256>");
        }
        if (!MODEL_SHA256.equals(args[4])) {
            throw new IllegalArgumentException("this gate requires the pinned licensed SmolLM2 release digest");
        }
        Path library = Path.of(args[0]).toRealPath();
        Path cache = Path.of(args[1]).toRealPath();
        Path work = Path.of(args[2]).toRealPath();
        Path root = Files.createTempDirectory(work, "real-gguf-").toRealPath();
        Path target = Files.createDirectory(root.resolve("target"));
        Path prompt = root.resolve("prompt.txt");
        Files.writeString(prompt, TREE_PROMPT, StandardCharsets.UTF_8);

        // A miss acquires the release through the production Java resolver. CI may
        // restore a verified cache, so this call does not assert that a download occurred.
        Path firstRecipe = recipe(root, "release.properties", prompt, library, cache, args[3]);
        Path first = SmartBuildMain.run(firstRecipe, root, target, Map.of(), StandaloneMain::backend);
        String candidate = verifyCandidate(first, firstRecipe, TREE_PROMPT);
        Path model = cache.resolve(MODEL_SHA256 + ".gguf");
        check(Files.size(model) == MODEL_BYTES, "published model byte length");
        check(hash(model).equals(MODEL_SHA256), "published model SHA-256");

        // Any attempted transport now fails; successful real inference requires a
        // verified cache hit, rather than a mocked downloader or provider.
        Path warmRecipe = recipe(root, "warm.properties", prompt, library, cache, UNREACHABLE_URL);
        Path warm = SmartBuildMain.run(warmRecipe, root, target, Map.of(), StandaloneMain::backend);
        check(verifyCandidate(warm, warmRecipe, TREE_PROMPT).equals(candidate),
                "warm-cache CPU inference must repeat the first result");

        rejectCorruptCache(root, target, prompt, library, cache, model);
        verifyNativeLifecycle(library, model, candidate);
        System.out.println("PASS: real GGUF CPU smart build, cache reuse, corruption refusal, receipts and close; checks="
                + checks);
    }

    private static Path recipe(Path root, String name, Path prompt, Path library,
                               Path cache, String modelUrl) throws IOException {
        Properties values = new Properties();
        values.setProperty("promptFile", prompt.toString());
        values.setProperty("backend", "jni");
        values.setProperty("jniLib", library.toString());
        values.setProperty("modelUrl", modelUrl);
        values.setProperty("modelSha256", MODEL_SHA256);
        values.setProperty("modelCacheDir", cache.toString());
        values.setProperty("computeBackend", "cpu");
        values.setProperty("gpuLayers", "0");
        values.setProperty("contextTokens", "512");
        values.setProperty("threads", "2");
        values.setProperty("maxTokens", "48");
        values.setProperty("timeoutSeconds", "90");
        Path recipe = root.resolve(name);
        try (Writer writer = Files.newBufferedWriter(recipe, StandardCharsets.UTF_8)) {
            values.store(writer, "Explicit real-model CPU test input");
        }
        return recipe;
    }

    private static String verifyCandidate(Path candidate, Path recipe, String prompt) throws Exception {
        long size = Files.size(candidate);
        check(size > 0 && size <= 4 * 1024 * 1024, "bounded real candidate bytes");
        String text = Files.readString(candidate, StandardCharsets.UTF_8);
        check(!text.isBlank(), "nonblank actual GGUF inference");
        String name = candidate.getFileName().toString();
        check(name.startsWith("candidate-") && name.endsWith(".txt"), "candidate file identity");
        Path receipt = candidate.resolveSibling("receipt-"
                + name.substring("candidate-".length(), name.length() - ".txt".length()) + ".properties");
        Properties recorded = new Properties();
        try (Reader reader = Files.newBufferedReader(receipt, StandardCharsets.UTF_8)) {
            recorded.load(reader);
        }
        check("llama.cpp-jni".equals(recorded.getProperty("backend")), "persisted native provider identity");
        check(hash(prompt).equals(recorded.getProperty("prompt.sha256")), "persisted prompt receipt");
        check(hash(candidate).equals(recorded.getProperty("candidate.sha256")), "persisted candidate receipt");
        check(hash(recipe).equals(recorded.getProperty("recipe.sha256")), "persisted recipe receipt");
        return text;
    }

    private static void rejectCorruptCache(Path root, Path target, Path prompt, Path library,
                                          Path cache, Path model) throws Exception {
        Path corruptRecipe = recipe(root, "corrupt.properties", prompt, library, cache, UNREACHABLE_URL);
        Map<String, String> before = outputs(target.resolve("smart-build"));
        long offset = MODEL_BYTES - 1;
        byte original;
        try (SeekableByteChannel input = Files.newByteChannel(model, StandardOpenOption.READ)) {
            input.position(offset);
            ByteBuffer one = ByteBuffer.allocate(1);
            if (input.read(one) != 1) throw new IOException("unable to read model corruption fixture byte");
            original = one.array()[0];
        }
        // Alter tensor data, keeping the GGUF header and size intact. A native
        // header parser alone must not be mistaken for content-hash validation.
        try {
            writeByte(model, offset, (byte) (original ^ 1));
            check(Files.size(model) == MODEL_BYTES, "corruption retains exact byte length");
            boolean refused = false;
            try {
                SmartBuildMain.run(corruptRecipe, root, target, Map.of(), StandaloneMain::backend);
            } catch (IOException expected) {
                refused = expected.getMessage() != null && expected.getMessage().contains("SHA-256");
            }
            check(refused, "same-size cached corruption must fail SHA-256 validation");
            check(outputs(target.resolve("smart-build")).equals(before),
                    "corrupt model must not publish or replace a candidate or receipt");
            check(!hash(model).equals(MODEL_SHA256), "corrupt cache must not be silently repaired");
        } finally {
            writeByte(model, offset, original);
            check(Files.size(model) == MODEL_BYTES && hash(model).equals(MODEL_SHA256),
                    "restore exact model bytes before retaining the cache");
        }
    }

    private static void verifyNativeLifecycle(Path library, Path model, String smartCandidate) throws Exception {
        ModelRequest request = new ModelRequest(TREE_PROMPT, 48, Duration.ofSeconds(90));
        LlamaCppJniBackend backend = new LlamaCppJniBackend(library, model, 512, 2, 0, "cpu");
        try {
            ModelResponse first = backend.infer(request);
            check("llama.cpp-jni".equals(first.backendId()), "native provider identity");
            check(first.promptSha256().equals(hash(TREE_PROMPT)), "native prompt receipt");
            check(!first.text().isBlank(), "native real GGUF output");
            check(first.text().getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024,
                    "native output byte bound");
            check(first.text().equals(smartCandidate), "Maven smart provider and direct CPU inference agree");
            ModelResponse repeated = backend.infer(request);
            check(repeated.text().equals(first.text()), "greedy CPU inference is repeatable");
            check(repeated.promptSha256().equals(hash(TREE_PROMPT)), "repeat prompt receipt");
            ModelResponse other = backend.infer(new ModelRequest(RIVER_PROMPT, 48, Duration.ofSeconds(90)));
            check(!other.text().isBlank(), "second distinct real prompt produces output");
            check(other.promptSha256().equals(hash(RIVER_PROMPT))
                    && !other.promptSha256().equals(first.promptSha256()), "distinct prompt receipt");
            backend.close();
            backend.close();
            boolean refused = false;
            try {
                backend.infer(request);
            } catch (IOException expected) {
                refused = true;
            }
            check(refused, "inference after native model close is refused");
        } finally {
            backend.close();
        }
    }

    private static void writeByte(Path path, long offset, byte value) throws IOException {
        try (SeekableByteChannel output = Files.newByteChannel(path, StandardOpenOption.WRITE)) {
            output.position(offset);
            ByteBuffer one = ByteBuffer.wrap(new byte[] {value});
            while (one.hasRemaining()) output.write(one);
        }
    }

    private static Map<String, String> outputs(Path directory) throws Exception {
        Map<String, String> snapshot = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) snapshot.put(path.getFileName().toString(), hash(path));
        }
        return snapshot;
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] block = new byte[64 * 1024];
            int count;
            while ((count = input.read(block)) != -1) digest.update(block, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void check(boolean condition, String contract) {
        checks++;
        if (!condition) throw new AssertionError("real GGUF contract failed: " + contract);
    }
}
