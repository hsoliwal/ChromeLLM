// SPDX-License-Identifier: MIT
// Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
// Agent additions: Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project; Apache-2.0.
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.net.URI;
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

/** Explicit provider selection for local, hosted or installed-Chrome inference and bounded stdio sessions. */
public final class StandaloneMain {
    private StandaloneMain() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        validateAgentMode(options);
        validateChromeModes(options);
        if (flag(options, "--agent") && options.containsKey("--agent-endpoint")) {
            Path goalFile = Path.of(required(options, "--prompt-file"));
            AgentSession.Limits limits = agentLimits(options);
            String goal = readAgentGoal(goalFile, limits);
            // Validate the tool authority before opening or downloading a model.
            try (CodingAgentTools tools = agentTools(options, System.getenv())) {
                AgentSession.validateInput(goal, tools.tools(), limits);
                try (ModelBackend selected = backend(options, System.getenv())) {
                    AgentSession.Result result = new AgentSession(selected, tools.tools(), limits).run(goal);
                    System.out.println(McpJson.encode(result.structured(), StdioProtocol.MAX_RESPONSE_BYTES));
                }
            }
            return;
        }
        Path workspace = agentWorkspace(options);
        List<AgentTool> tools = workspace == null ? null : new WorkspaceTools(workspace).tools();
        if (flag(options, "--chrome-status") || flag(options, "--chrome-prepare")) {
            Duration timeout = setupTimeout(options);
            try (ModelBackend selected = backend(options, System.getenv())) {
                ChromeAiBackend chrome = (ChromeAiBackend) selected;
                Map<String, Object> result = flag(options, "--chrome-status")
                        ? chrome.status(timeout) : chrome.prepare(timeout);
                System.out.println(McpJson.encode(result, 64 * 1024));
            }
            return;
        }
        if (flag(options, "--mcp-stdio")) {
            if (flag(options, "--stdio") || flag(options, "--list-devices")
                    || options.containsKey("--prompt-file")) {
                throw new IllegalArgumentException("MCP stdio requires its own exclusive session mode");
            }
            AgentSession.Limits endpointLimits = options.containsKey("--agent-endpoint")
                    ? agentLimits(options) : AgentSession.Limits.defaults();
            try (CodingAgentTools endpointTools = options.containsKey("--agent-endpoint")
                         ? agentTools(options, System.getenv()) : null;
                 ModelBackend backend = backend(options, System.getenv());
                 FileOutputStream output = new FileOutputStream(FileDescriptor.out)) {
                McpStdioServer.serve(backend, System.in, output, tools,
                        endpointTools == null ? List.of() : endpointTools.tools(), endpointLimits);
            }
            return;
        }
        if (flag(options, "--list-devices")) {
            if (!"jni".equals(options.getOrDefault("--backend", "jni"))) {
                throw new IllegalArgumentException("device listing requires the JNI backend");
            }
            for (LlamaCppJniBackend.Device device : LlamaCppJniBackend.availableDevices(jniLibrary(options))) {
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

    /** Authority validation runs before provider or cache initialization. */
    private static Path agentWorkspace(Map<String, String> options) {
        return options.containsKey("--workspace") ? Path.of(required(options, "--workspace")) : null;
    }

    /** CLI execution selects one agent engine; MCP can expose both independently configured tools. */
    static void validateAgentMode(Map<String, String> options) {
        boolean agent = flag(options, "--agent");
        boolean mcp = flag(options, "--mcp-stdio");
        boolean workspace = options.containsKey("--workspace");
        boolean endpoint = options.containsKey("--agent-endpoint");
        boolean endpointControls = endpoint || options.containsKey("--agent-token-env")
                || options.containsKey("--agent-max-context-bytes")
                || options.containsKey("--agent-max-tool-result-bytes");
        boolean configured = options.keySet().stream().anyMatch(key -> key.startsWith("--agent-"));
        if (agent && (mcp || flag(options, "--stdio") || flag(options, "--list-devices")
                || flag(options, "--chrome-status") || flag(options, "--chrome-prepare"))) {
            throw new IllegalArgumentException("agent execution requires its own exclusive session mode");
        }
        if (agent && workspace && endpoint) {
            throw new IllegalArgumentException("agent execution requires either --workspace or --agent-endpoint, not both");
        }
        if (configured && !agent && !mcp) {
            throw new IllegalArgumentException("agent controls require --agent true or --mcp-stdio true");
        }
        if (workspace && !agent && !mcp) {
            throw new IllegalArgumentException("workspace requires --agent true or --mcp-stdio true");
        }
        if (endpointControls) {
            required(options, "--agent-endpoint");
            required(options, "--agent-token-env");
            agentLimits(options);
        }
        if (agent && !endpoint) required(options, "--workspace");
        if (options.containsKey("--agent-max-calls") && (!agent || endpoint)) {
            throw new IllegalArgumentException("agent model-call limit requires workspace agent execution");
        }
        if (!agent && !endpoint && options.containsKey("--agent-max-steps")) {
            throw new IllegalArgumentException("agent limits require an enabled agent engine");
        }
    }

    private static AgentEngine.Limits agentLimits(Map<String, String> options, Duration timeout) {
        return new AgentEngine.Limits(Integer.parseInt(options.getOrDefault("--agent-max-steps", "16")),
                Integer.parseInt(options.getOrDefault("--agent-max-calls", "8")), 65536, 32768, timeout);
    }

    static AgentSession.Limits agentLimits(Map<String, String> options) {
        return new AgentSession.Limits(
                Integer.parseInt(options.getOrDefault("--agent-max-steps", "8")),
                Integer.parseInt(options.getOrDefault("--max-tokens", "1024")),
                Integer.parseInt(options.getOrDefault("--agent-max-context-bytes", "65536")),
                Integer.parseInt(options.getOrDefault("--agent-max-tool-result-bytes", "16384")),
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("--timeout-seconds", "120"))));
    }

    static CodingAgentTools agentTools(Map<String, String> options, Map<String, String> environment) {
        String variable = required(options, "--agent-token-env");
        if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("--agent-token-env must name an environment variable");
        }
        String credential = environment.get(variable);
        if (credential == null || !credential.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("coding-agent token environment variable is not valid");
        }
        return new CodingAgentTools(URI.create(required(options, "--agent-endpoint")), credential);
    }

    static String readPrompt(Path promptFile, boolean hosted) throws IOException {
        int limit = hosted ? GeminiApiBackend.MAX_REQUEST_BYTES : StdioProtocol.MAX_PROMPT_BYTES;
        return readTextPrompt(promptFile, limit, hosted ? "Gemini" : "local");
    }

    static String readAgentGoal(Path promptFile, AgentSession.Limits limits) throws IOException {
        if (!Files.isRegularFile(promptFile)) throw new IOException("agent goal must be a regular file");
        String goal = readTextPrompt(promptFile, limits.maxContextBytes(), "agent");
        if (goal.isBlank()) throw new IllegalArgumentException("agent goal must not be blank");
        return goal;
    }

    private static String readTextPrompt(Path promptFile, int limit, String kind) throws IOException {
        // Bound the read itself: a size-only check could race a growing prompt file.
        try (var input = Files.newInputStream(promptFile)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException(kind + " prompt file exceeds the bounded input limit");
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    static ModelBackend backend(Map<String, String> options, Map<String, String> environment) throws Exception {
        String provider = options.getOrDefault("--backend", "cli");
        validateChromeModes(options);
        if ("chrome".equals(provider)) {
            rejectOptions(options, "--model", "--llama-bin", "--jni-lib", "--context-tokens", "--threads",
                    "--gpu-layers", "--compute-backend", "--device-backend", "--device", "--tier",
                    "--fast-model", "--reasoning-model", "--frontier-model", "--model-url",
                    "--model-sha256", "--model-cache-dir", "--model-follow-redirects",
                    "--model-download-timeout-seconds", "--model-max-bytes", "--thinking-level", "--api-key-env", "--list-devices");
            return new ChromeAiBackend(chromeExecutable(options, environment),
                    Path.of(required(options, "--chrome-profile")),
                    flag(options, "--chrome-headless"), flag(options, "--chrome-download"));
        }
        if ("gemini".equals(provider)) {
            rejectOptions(options, "--model-url", "--model-sha256", "--model-cache-dir",
                    "--model-follow-redirects", "--model-download-timeout-seconds", "--model-max-bytes");
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
                || options.containsKey("--model-cache-dir") || options.containsKey("--model-follow-redirects")
                || options.containsKey("--model-download-timeout-seconds") || options.containsKey("--model-max-bytes")) {
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
                Path library = jniLibrary(options);
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
            default -> throw new IllegalArgumentException("unknown backend (expected cli, jni, gemini or chrome)");
        };
    }

    private static void validateChromeModes(Map<String, String> options) {
        if (!"chrome".equals(options.get("--backend"))) {
            rejectOptions(options, "--chrome-bin", "--chrome-profile", "--chrome-headless",
                    "--chrome-download", "--chrome-status", "--chrome-prepare");
            return;
        }
        boolean status = flag(options, "--chrome-status");
        boolean prepare = flag(options, "--chrome-prepare");
        if (status && prepare) throw new IllegalArgumentException("Chrome status and preparation are exclusive modes");
        if (status || prepare) {
            rejectOptions(options, "--prompt-file", "--stdio", "--mcp-stdio", "--list-devices", "--max-tokens",
                    "--agent", "--workspace", "--agent-endpoint", "--agent-token-env", "--agent-max-steps",
                    "--agent-max-calls", "--agent-max-context-bytes", "--agent-max-tool-result-bytes");
        }
    }

    private static Duration setupTimeout(Map<String, String> options) {
        long seconds = Long.parseLong(options.getOrDefault("--timeout-seconds", "120"));
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("timeout must be in [1, 3600] seconds");
        return Duration.ofSeconds(seconds);
    }

    /** Discovery selects an executable only; it never attaches to an existing browser or profile. */
    private static Path chromeExecutable(Map<String, String> options, Map<String, String> environment) {
        String selected = options.containsKey("--chrome-bin") ? required(options, "--chrome-bin")
                : environment.get("CHROME_BIN");
        if (selected != null && !selected.isBlank()) return executable(Path.of(selected));
        java.util.ArrayList<Path> candidates = new java.util.ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            for (String variable : java.util.List.of("PROGRAMFILES", "ProgramFiles", "PROGRAMFILES(X86)",
                    "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String folder = environment.get(variable);
                if (folder != null && !folder.isBlank()) candidates.add(Path.of(folder, "Google", "Chrome", "Application", "chrome.exe"));
            }
        } else if (os.contains("mac")) {
            candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            candidates.add(Path.of("/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary"));
            candidates.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        } else {
            for (String path : java.util.List.of("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
                    "/usr/bin/chromium", "/usr/bin/chromium-browser", "/snap/bin/chromium")) candidates.add(Path.of(path));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize();
        }
        throw new IllegalArgumentException("Installed Chrome was not found; set --chrome-bin or CHROME_BIN to its executable");
    }

    private static Path executable(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute) || !Files.isExecutable(absolute)) {
            throw new IllegalArgumentException("Chrome executable is not an executable regular file");
        }
        return absolute;
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
            if (options.containsKey("--jni-lib")) required(options, "--jni-lib");
            if (options.containsKey("--device-backend") || options.containsKey("--device")) {
                rejectOptions(options, "--compute-backend");
                required(options, "--device-backend");
                required(options, "--device");
            }
        }
        boolean explicitPolicy = options.containsKey("--model-follow-redirects")
                || options.containsKey("--model-download-timeout-seconds")
                || options.containsKey("--model-max-bytes");
        ModelArtifactCache artifact = explicitPolicy
                ? new ModelArtifactCache(url, sha256, cacheDir, flag(options, "--model-follow-redirects"),
                    Duration.ofSeconds(Long.parseLong(options.getOrDefault("--model-download-timeout-seconds", "3600"))),
                    Long.parseLong(options.getOrDefault("--model-max-bytes", Long.toString(ModelArtifactCache.MAX_BYTES))))
                : new ModelArtifactCache(url, sha256, cacheDir);
        Map<String, String> retained = new HashMap<>(options);
        retained.remove("--model-url");
        retained.remove("--model-sha256");
        retained.remove("--model-cache-dir");
        retained.remove("--model-follow-redirects");
        retained.remove("--model-download-timeout-seconds");
        retained.remove("--model-max-bytes");
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

    private static Path jniLibrary(Map<String, String> options) throws IOException {
        return options.containsKey("--jni-lib")
                ? Path.of(required(options, "--jni-lib")) : NativeLibraryLoader.libraryPath();
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
                "--model-cache-dir", "--model-follow-redirects", "--model-download-timeout-seconds",
                "--model-max-bytes", "--mcp-stdio", "--agent", "--agent-endpoint", "--agent-token-env",
                "--agent-max-steps", "--agent-max-context-bytes", "--agent-max-tool-result-bytes",
                "--workspace", "--agent-max-calls", "--chrome-bin", "--chrome-profile", "--chrome-headless",
                "--chrome-download", "--chrome-status", "--chrome-prepare");
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
