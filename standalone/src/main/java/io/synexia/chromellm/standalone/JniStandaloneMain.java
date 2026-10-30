// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** CLI for the in-process JNI backend. The existing llama-cli route remains separate. */
public final class JniStandaloneMain {
    private JniStandaloneMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Path prompt = Path.of(required(options, "--prompt-file"));
        if (!Files.isRegularFile(prompt)) throw new IllegalArgumentException("prompt file missing");
        ModelRequest request = new ModelRequest(
                Files.readString(prompt, StandardCharsets.UTF_8),
                Integer.parseInt(options.getOrDefault("--max-tokens", "256")),
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("--timeout-seconds", "120"))));
        try (ModelBackend backend = new JniLlamaCppBackend(
                Path.of(required(options, "--jni-library")), Path.of(required(options, "--model")))) {
            System.out.print(backend.infer(request).text());
        }
    }

    private static String required(Map<String, String> options, String name) {
        String v = options.get(name);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + name);
        return v;
    }

    private static Map<String, String> parse(String[] args) {
        if ((args.length & 1) != 0) throw new IllegalArgumentException("options require values");
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            if (!name.equals("--jni-library") && !name.equals("--model")
                    && !name.equals("--prompt-file") && !name.equals("--max-tokens")
                    && !name.equals("--timeout-seconds")) {
                throw new IllegalArgumentException("unknown option " + name);
            }
            if (result.putIfAbsent(name, args[i + 1]) != null)
                throw new IllegalArgumentException("duplicate option " + name);
        }
        return Map.copyOf(result);
    }
}
