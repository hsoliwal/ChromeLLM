// SPDX-License-Identifier: MIT
// Agent additions: Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project; Apache-2.0.
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit browser-free inference with a local or hosted provider, once or through a bounded stdio session. */
public final class StandaloneMain {
    private StandaloneMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Path workspace = agentWorkspace(options);
        List<AgentTool> tools = workspace == null ? null : new WorkspaceTools(workspace).tools();
        if (flag(options, "--mcp-stdio")) {
            if (flag(options, "--stdio") || flag(options, "--list-devices")
                    || options.containsKey("--prompt-file")) {
                throw new IllegalArgumentException("MCP stdio requires its own exclusive session mode");
            }
            try (ModelBackend backend = backend(options, System.getenv());
                 FileOutputStream output = new FileOutputStream(FileDescriptor.out)) {
                McpStdioServer.serveTools(backend, System.in, output, tools);
            }
            return;
        }
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
            try (ModelBackend backend = backend(options, System.getenv());
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
                readPrompt(promptFile, "gemini".equals(options.get("--backend"))),
                Integer.parseInt(options.getOrDefault("--max-tokens",
                        flag(options, "--agent") ? "1024" : "gemini".equals(options.get("--backend")) ? "32768" : "512")),
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("--timeout-seconds", "120"))));
        AgentEngine.Limits limits = flag(options, "--agent") ? agentLimits(options, request.timeout()) : null;
        try (ModelBackend backend = backend(options, System.getenv())) {
            if (limits == null) {
                System.out.print(backend.infer(request).text());
            } else {
                AgentEngine agent = new AgentEngine(backend, tools, event ->
                        System.err.println("agent " + event.sequence() + " " + event.phase() + " " + event.tool()));
                System.out.print(agent.run(request.prompt(), request.maxTokens(), limits).answer());
            }
        }
    }

    /** Reject mode mistakes before loading a provider or touching the model cache. */
    private static Path agentWorkspace(Map<String, String> options) {
        boolean agent = flag(options, "--agent");
        boolean mcp = flag(options, "--mcp-stdio");
        if (agent && (mcp || flag(options, "--stdio") || flag(options, "--list-devices"))) {
            throw new IllegalArgumentException("agent mode requires its own exclusive session mode");
        }
        if (!agent && (options.containsKey("--agent-max-steps") || options.containsKey("--agent-max-calls"))) {
            throw new IllegalArgumentException("agent limits require --agent true");
        }
        if (options.containsKey("--workspace") && !agent && !mcp) {
            throw new IllegalArgumentException("workspace requires --agent true or --mcp-stdio true");
        }
        if (agent || options.containsKey("--workspace")) return Path.of(required(options, "--workspace"));
        return null;
    }

    private static AgentEngine.Limits agentLimits(Map<String, String> options, Duration timeout) {
        return new AgentEngine.Limits(Integer.parseInt(options.getOrDefault("--agent-max-steps", "16")),
                Integer.parseInt(options.getOrDefault("--agent-max-calls", "8")), 65536, 32768, timeout);
    }

    static String readPrompt(Path promptFile, boolean hosted) throws IOException {
        int limit = hosted ? GeminiApiBackend.MAX_REQUEST_BYTES : StdioProtocol.MAX_PROMPT_BYTES;
        // Bound the read itself: a size-only check could race a growing prompt file.
        try (var input = Files.newInputStream(promptFile)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException((hosted ? "Gemini" : "local")
                        + " prompt file exceeds the bounded input limit");
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
            rejectOptions(options, "--model-url", "--model-sha256", "--model-cache-dir");
            rejectOptions(options, "--llama-bin", "--jni-lib", "--context-tokens", "--threads",
                    "--gpu-layers", "--compute-backend", "--device-backend", "--device", "--tier",
                    "--fast-model", "--reasoning-model", "--frontier-model");
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
        if (options.containsKey("--model-url") || options.containsKey("--model-sha256")
                || options.containsKey("--model-cache-dir")) {
            return lazyLocalModel(options, environment, provider);
        }
        Path model = localModel(options);
        return switch (provider) {
            case "cli" -> {
                // --device alone names the llama.cpp CLI device; the backend/device pair is JNI-only.
                rejectOptions(options, "--compute-backend", "--device-backend", "--jni-lib",
                        "--context-tokens", "--threads");
                yield options.containsKey("--device") || options.containsKey("--gpu-layers")
                        ? new LlamaCppCliBackend(Path.of(required(options, "--llama-bin")), model,
                                options.get("--device"),
                                Integer.parseInt(options.getOrDefault("--gpu-layers", "-1")))
                        : new LlamaCppCliBackend(Path.of(required(options, "--llama-bin")), model);
            }
            case "jni" -> {
                rejectOptions(options, "--llama-bin");
                Path library = Path.of(required(options, "--jni-lib"));
                int context = Integer.parseInt(options.getOrDefault("--context-tokens", "4096"));
                int threads = Integer.parseInt(options.getOrDefault("--threads", "4"));
                int layers = Integer.parseInt(options.getOrDefault("--gpu-layers", "0"));
                if (options.containsKey("--device-backend") || options.containsKey("--device")) {
                    // One observed backend/device pair; a provider policy would contradict it.
                    rejectOptions(options, "--compute-backend");
                    yield new LlamaCppJniBackend(library, model, context, threads, layers,
                            required(options, "--device-backend"), required(options, "--device"));
                }
                yield new LlamaCppJniBackend(library, model, context, threads, layers,
                        options.getOrDefault("--compute-backend", "auto"));
            }
            default -> throw new IllegalArgumentException("unknown backend (expected cli, jni or gemini)");
        };
    }

    /** Optional model URL is downloaded only on the first inference; existing paths remain untouched. */
    private static ModelBackend lazyLocalModel(Map<String, String> options,
                                                Map<String, String> environment, String provider) {
        if (!"cli".equals(provider) && !"jni".equals(provider)) {
            throw new IllegalArgumentException("lazy model artifacts require the cli or jni backend");
        }
        rejectOptions(options, "--model", "--tier", "--fast-model",
                "--reasoning-model", "--frontier-model");
        String url = required(options, "--model-url");
        String sha256 = required(options, "--model-sha256");
        Path cacheDir = Path.of(required(options, "--model-cache-dir"));
        // Validate the local provider selection before any network or cache writes.
        if ("cli".equals(provider)) {
            rejectOptions(options, "--compute-backend", "--device-backend", "--jni-lib",
                    "--context-tokens", "--threads");
            required(options, "--llama-bin");
        } else {
            rejectOptions(options, "--llama-bin");
            required(options, "--jni-lib");
            if (options.containsKey("--device-backend") || options.containsKey("--device")) {
                rejectOptions(options, "--compute-backend");
                required(options, "--device-backend");
                required(options, "--device");
            }
        }
        ModelArtifactCache artifact = new ModelArtifactCache(url, sha256, cacheDir);
        Map<String, String> retained = new HashMap<>(options);
        retained.remove("--model-url");
        retained.remove("--model-sha256");
        retained.remove("--model-cache-dir");
        String backendId = "cli".equals(provider) ? "llama.cpp-cli" : "llama.cpp-jni";
        return new LazyModelBackend(backendId, artifact, resolved -> {
            Map<String, String> local = new HashMap<>(retained);
            local.put("--model", resolved.toString());
            return backend(local, environment);
        });
    }

    /** One local model path: --model, or the path named for the explicitly selected --tier. */
    private static Path localModel(Map<String, String> options) {
        String tierValue = options.get("--tier");
        if (tierValue == null) {
            if (options.containsKey("--fast-model") || options.containsKey("--reasoning-model")
                    || options.containsKey("--frontier-model")) {
                throw new IllegalArgumentException("tier-specific models require --tier");
            }
            return Path.of(required(options, "--model"));
        }
        if (options.containsKey("--model")) {
            throw new IllegalArgumentException("--model and --tier-specific model paths are exclusive");
        }
        String key = switch (InferenceTier.parse(tierValue)) {
            case FAST -> "--fast-model";
            case REASONING -> "--reasoning-model";
            case FRONTIER -> "--frontier-model";
        };
        return Path.of(required(options, key));
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

    static Map<String, String> parse(String[] args) {
        if ((args.length & 1) != 0) throw new IllegalArgumentException("options require values");
        Set<String> allowed = Set.of("--llama-bin", "--backend", "--jni-lib", "--context-tokens",
                "--threads", "--gpu-layers", "--model", "--prompt-file", "--max-tokens",
                "--timeout-seconds", "--stdio", "--list-devices", "--device-backend", "--device",
                "--compute-backend", "--thinking-level", "--api-key-env", "--tier", "--fast-model",
                "--reasoning-model", "--frontier-model", "--model-url", "--model-sha256",
                "--model-cache-dir", "--mcp-stdio", "--agent", "--workspace",
                "--agent-max-steps", "--agent-max-calls");
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
