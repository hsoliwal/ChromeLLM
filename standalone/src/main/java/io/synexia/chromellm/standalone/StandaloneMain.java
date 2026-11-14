// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Exactly one bounded invocation of an explicitly selected local or hosted provider. */
public final class StandaloneMain {
    private StandaloneMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Path promptFile = Path.of(required(options, "--prompt-file"));
        if (!Files.isRegularFile(promptFile)) {
            throw new IllegalArgumentException("prompt file does not exist");
        }
        ModelRequest request = new ModelRequest(
                readPrompt(promptFile, "gemini".equals(options.get("--backend"))),
                Integer.parseInt(options.getOrDefault("--max-tokens",
                        "gemini".equals(options.get("--backend")) ? "32768" : "512")),
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("--timeout-seconds", "120"))));
        try (ModelBackend backend = backend(options, System.getenv())) {
            System.out.print(backend.infer(request).text());
        }
    }

    static String readPrompt(Path promptFile, boolean hosted) throws IOException {
        if (!hosted) return Files.readString(promptFile, StandardCharsets.UTF_8);
        // Bound the read itself: a size-only check could race a growing prompt file.
        try (var input = Files.newInputStream(promptFile)) {
            byte[] bytes = input.readNBytes(GeminiApiBackend.MAX_REQUEST_BYTES + 1);
            if (bytes.length > GeminiApiBackend.MAX_REQUEST_BYTES) {
                throw new IOException("Gemini prompt file exceeds the bounded input limit");
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    static ModelBackend backend(Map<String, String> options, Map<String, String> environment) throws Exception {
        String provider = options.getOrDefault("--backend", "cli");
        if ("gemini".equals(provider)) {
            rejectOptions(options, "--llama-bin", "--jni-lib", "--context-tokens", "--threads",
                    "--gpu-layers", "--compute-backend");
            String keyVariable = options.getOrDefault("--api-key-env", "GEMINI_API_KEY");
            if (!keyVariable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("--api-key-env must name an environment variable");
            }
            String apiKey = environment.get(keyVariable);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Gemini API key environment variable is not set");
            }
            return new GeminiApiBackend(apiKey,
                    options.getOrDefault("--model", GeminiApiBackend.DEFAULT_MODEL),
                    options.getOrDefault("--thinking-level", GeminiApiBackend.DEFAULT_THINKING_LEVEL));
        }
        rejectOptions(options, "--thinking-level", "--api-key-env");
        return switch (provider) {
            case "cli" -> {
                rejectOptions(options, "--compute-backend");
                yield new LlamaCppCliBackend(Path.of(required(options, "--llama-bin")),
                        Path.of(required(options, "--model")));
            }
            case "jni" -> new LlamaCppJniBackend(
                    Path.of(required(options, "--jni-lib")), Path.of(required(options, "--model")),
                    Integer.parseInt(options.getOrDefault("--context-tokens", "4096")),
                    Integer.parseInt(options.getOrDefault("--threads", "4")),
                    Integer.parseInt(options.getOrDefault("--gpu-layers", "0")),
                    options.getOrDefault("--compute-backend", "auto"));
            default -> throw new IllegalArgumentException("unknown backend (expected cli, jni or gemini)");
        };
    }

    private static void rejectOptions(Map<String, String> options, String... incompatible) {
        for (String option : incompatible) {
            if (options.containsKey(option)) {
                throw new IllegalArgumentException(option + " is not supported by the selected backend");
            }
        }
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    static Map<String, String> parse(String[] args) {
        if ((args.length & 1) != 0) throw new IllegalArgumentException("options require values");
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String option = args[i];
            if (!option.equals("--llama-bin") && !option.equals("--backend") && !option.equals("--jni-lib")
                    && !option.equals("--context-tokens") && !option.equals("--threads")
                    && !option.equals("--gpu-layers") && !option.equals("--model")
                    && !option.equals("--compute-backend") && !option.equals("--thinking-level")
                    && !option.equals("--api-key-env")
                    && !option.equals("--prompt-file") && !option.equals("--max-tokens")
                    && !option.equals("--timeout-seconds")) {
                throw new IllegalArgumentException("unknown option " + option);
            }
            if (options.putIfAbsent(option, args[i + 1]) != null) {
                throw new IllegalArgumentException("duplicate option " + option);
            }
        }
        return Map.copyOf(options);
    }
}
