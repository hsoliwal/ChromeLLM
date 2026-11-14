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
        String tierValue = options.get("--tier");
        final Path model;
        if (tierValue == null) {
            if (options.containsKey("--fast-model") || options.containsKey("--reasoning-model")
                    || options.containsKey("--frontier-model")) {
                throw new IllegalArgumentException("tier-specific models require --tier");
            }
            model = Path.of(required(options, "--model"));
        } else {
            if (options.containsKey("--model")) {
                throw new IllegalArgumentException("--model and --tier-specific model paths are exclusive");
            }
            InferenceTier tier = InferenceTier.parse(tierValue);
            String key = switch (tier) {
                case FAST -> "--fast-model";
                case REASONING -> "--reasoning-model";
                case FRONTIER -> "--frontier-model";
            };
            model = Path.of(required(options, key));
        }
        return switch (options.getOrDefault("--backend", "cli")) {
            case "cli" -> options.containsKey("--device") || options.containsKey("--gpu-layers")
                    ? new LlamaCppCliBackend(Path.of(required(options, "--llama-bin")), model,
                            options.get("--device"), Integer.parseInt(options.getOrDefault("--gpu-layers", "-1")))
                    : new LlamaCppCliBackend(Path.of(required(options, "--llama-bin")), model);
            case "jni" -> {
                if (options.containsKey("--device")) {
                    throw new IllegalArgumentException("explicit --device currently requires --backend cli");
                }
                yield new LlamaCppJniBackend(
                    Path.of(required(options, "--jni-lib")), model,
                    Integer.parseInt(options.getOrDefault("--context-tokens", "4096")),
                    Integer.parseInt(options.getOrDefault("--threads", "4")),
                    Integer.parseInt(options.getOrDefault("--gpu-layers", "0")));
            }
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
                    && !option.equals("--timeout-seconds") && !option.equals("--device")
                    && !option.equals("--tier") && !option.equals("--fast-model")
                    && !option.equals("--reasoning-model") && !option.equals("--frontier-model")) {
                throw new IllegalArgumentException("unknown option " + option);
            }
            if (options.putIfAbsent(option, args[i + 1]) != null) {
                throw new IllegalArgumentException("duplicate option " + option);
            }
        }
        return Map.copyOf(options);
    }
}
