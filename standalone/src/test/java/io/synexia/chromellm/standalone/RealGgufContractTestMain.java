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

/** Opt-in real GGUF CPU gate: obtains pinned weights and invokes the supplied JNI runtime. */
public final class RealGgufContractTestMain {
    private static final byte[] GGUF_MAGIC = {'G', 'G', 'U', 'F'};
    private static final String PROMPT = "<|im_start|>user\n"
            + "Write one short sentence about an oak tree.<|im_end|>\n"
            + "<|im_start|>assistant\n";
    private static final String OTHER_PROMPT = "<|im_start|>user\n"
            + "Write one short sentence about a river.<|im_end|>\n"
            + "<|im_start|>assistant\n";

    private RealGgufContractTestMain() { }

    public static void main(String[] args) throws Exception {
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
        String expectedPromptHash = digest(PROMPT);
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
            checkResponse(other, backend.id(), digest(OTHER_PROMPT));
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

    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean success, String contract) {
        if (!success) throw new AssertionError("real GGUF contract failed: " + contract);
    }
}
