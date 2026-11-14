// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Explicit browser-free inference, once or through a bounded persistent stdio session. */
public final class StandaloneMain {
    private StandaloneMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (flag(options, "--list-devices")) {
            if (!"jni".equals(options.getOrDefault("--backend", "jni"))) {
                throw new IllegalArgumentException("device listing requires the JNI backend");
            }
            for (LlamaCppJniBackend.Device device : LlamaCppJniBackend.availableDevices(
                    Path.of(required(options, "--jni-lib")))) {
                System.out.println(field(device.backend()) + "\t" + field(device.name()) + "\t"
                        + device.type() + "\t" + field(device.description()));
            }
            return;
        }
        if (flag(options, "--stdio")) {
            if (options.containsKey("--prompt-file")) {
                throw new IllegalArgumentException("stdio requests supply their own prompts");
            }
            try (ModelBackend backend = backend(options);
                 FileOutputStream output = new FileOutputStream(FileDescriptor.out)) {
                // PrintStream suppresses pipe failures; binary framing needs the original IOException.
                StdioProtocol.serve(backend, System.in, output);
            }
            return;
        }
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
        String selection = options.getOrDefault("--backend", "cli");
        boolean explicitDevice = options.containsKey("--device-backend") || options.containsKey("--device");
        if (explicitDevice && !"jni".equals(selection)) {
            throw new IllegalArgumentException("explicit device selection requires the JNI backend");
        }
        if ("cli".equals(selection)) {
            return new LlamaCppCliBackend(Path.of(required(options, "--llama-bin")), model);
        }
        if (!"jni".equals(selection)) {
            throw new IllegalArgumentException("unknown backend (expected cli or jni)");
        }
        Path library = Path.of(required(options, "--jni-lib"));
        int context = Integer.parseInt(options.getOrDefault("--context-tokens", "4096"));
        int threads = Integer.parseInt(options.getOrDefault("--threads", "4"));
        int layers = Integer.parseInt(options.getOrDefault("--gpu-layers", "0"));
        return explicitDevice
                ? new LlamaCppJniBackend(library, model, context, threads, layers,
                        required(options, "--device-backend"), required(options, "--device"))
                : new LlamaCppJniBackend(library, model, context, threads, layers);
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static boolean flag(Map<String, String> options, String key) {
        String value = options.getOrDefault(key, "false");
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " requires true or false");
        }
        return value.equals("true");
    }

    private static String field(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.toString();
    }

    private static Map<String, String> parse(String[] args) {
        if ((args.length & 1) != 0) throw new IllegalArgumentException("options require values");
        Set<String> allowed = Set.of("--llama-bin", "--backend", "--jni-lib", "--context-tokens",
                "--threads", "--gpu-layers", "--model", "--prompt-file", "--max-tokens",
                "--timeout-seconds", "--stdio", "--list-devices", "--device-backend", "--device");
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String option = args[i];
            if (!allowed.contains(option)) throw new IllegalArgumentException("unknown option " + option);
            if (options.putIfAbsent(option, args[i + 1]) != null) {
                throw new IllegalArgumentException("duplicate option " + option);
            }
        }
        return Map.copyOf(options);
    }
}
