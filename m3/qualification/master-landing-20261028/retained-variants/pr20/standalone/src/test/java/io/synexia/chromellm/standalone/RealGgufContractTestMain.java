// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Real model execution gate: never run generated text as code or assert factual accuracy. */
public final class RealGgufContractTestMain {
    private RealGgufContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: RealGgufContractTestMain <native-library> <licensed-gguf>");
        }
        Path library = Path.of(args[0]);
        Path model = Path.of(args[1]);
        if (!Files.isRegularFile(model) || !Files.isReadable(model)) {
            throw new IllegalArgumentException("model must be a readable GGUF file");
        }
        String prompt = "<|im_start|>user\nWrite one short sentence about an oak tree.<|im_end|>\n"
                + "<|im_start|>assistant\n";
        ModelRequest request = new ModelRequest(prompt, 48, Duration.ofSeconds(90));
        String expectedDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(prompt.getBytes(StandardCharsets.UTF_8)));
        JniLlamaCppBackend backend = new JniLlamaCppBackend(library, model);
        try {
            ModelResponse first = backend.infer(request);
            check(first.backendId().equals("llama.cpp-jni-cpu"), "native provider identity");
            check(first.promptSha256().equals(expectedDigest), "prompt receipt SHA-256");
            check(!first.text().isBlank(), "first actual GGUF result must be nonblank");
            check(first.text().getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024,
                    "bounded native output");

            ModelResponse repeated = backend.infer(request);
            check(repeated.text().equals(first.text()), "greedy CPU output must be repeatable");
            check(repeated.promptSha256().equals(expectedDigest), "repeat prompt receipt");

            ModelRequest different = new ModelRequest(
                    "<|im_start|>user\nWrite one short sentence about a river.<|im_end|>\n"
                            + "<|im_start|>assistant\n", 48, Duration.ofSeconds(90));
            ModelResponse other = backend.infer(different);
            check(!other.text().isBlank(), "subsequent real GGUF inference");
            check(!other.promptSha256().equals(expectedDigest), "different prompt receipts");
            backend.close();
            backend.close();
            boolean refused = false;
            try {
                backend.infer(request);
            } catch (IllegalStateException expected) {
                refused = true;
            }
            check(refused, "inference after native model close must be refused");
        } finally {
            backend.close();
        }
        System.out.println("PASS: licensed GGUF CPU inference, deterministic repeat, receipts and close");
    }

    private static void check(boolean success, String contract) {
        if (!success) {
            throw new AssertionError("real GGUF contract failed: " + contract);
        }
    }
}
