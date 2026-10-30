// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Exactly one bounded invocation of an explicitly selected, browser-free provider. */
public final class StandaloneMain {
    private StandaloneMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Path promptFile = Path.of(required(options, "--prompt-file"));
        if (!Files.isRegularFile(promptFile)) {
            throw new IllegalArgumentException("prompt file does not exist");
        }
        ModelRequest request = new ModelRequest(
                Files.readString(promptFile, StandardCharsets.UTF_8),
                Integer.parseInt(options.getOrDefault("--max-tokens", "512")),
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("--timeout-seconds", "120"))));
        try (ModelBackend backend = backend(options)) {
            System.out.print(backend.infer(request).text());
        }
    }

    private static ModelBackend backend(Map<String, String> options) throws Exception {
        Path model = Path.of(required(options, "--model"));
        return switch (options.getOrDefault("--backend", "cli")) {
            case "cli" -> new LlamaCppCliBackend(Path.of(required(options, "--llama-bin")), model);
            case "jni" -> new LlamaCppJniBackend(
                    Path.of(required(options, "--jni-lib")), model,
                    Integer.parseInt(options.getOrDefault("--context-tokens", "4096")),
                    Integer.parseInt(options.getOrDefault("--threads", "4")),
                    Integer.parseInt(options.getOrDefault("--gpu-layers", "0")));
            default -> throw new IllegalArgumentException("unknown backend (expected cli or jni)");
        };
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static Map<String, String> parse(String[] args) {
        if ((args.length & 1) != 0) throw new IllegalArgumentException("options require values");
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String option = args[i];
            if (!option.equals("--llama-bin") && !option.equals("--backend") && !option.equals("--jni-lib")
                    && !option.equals("--context-tokens") && !option.equals("--threads")
                    && !option.equals("--gpu-layers") && !option.equals("--model")
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
