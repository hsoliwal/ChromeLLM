// SPDX-License-Identifier: MIT AND Apache-2.0
// Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
// Workspace-agent fixture: Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
package io.synexia.chromellm.standalone;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses the real JNI bridge with synthetic model output; does not claim model quality or GPU execution. */
public final class JniAgentContractTestMain {
    private JniAgentContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("pass synthetic JNI library path");
        Path model = Files.createTempFile("agent-require-cpu-", ".gguf");
        try {
            Files.writeString(model, "synthetic protocol fixture, not model weights");
            AtomicInteger calls = new AtomicInteger();
            AgentSession.Tool tool = new AgentSession.Tool("fixture_status", "Read fixture status",
                    Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                    (arguments, remaining) -> {
                        if (!arguments.isEmpty()) throw new AssertionError("unexpected arguments");
                        calls.incrementAndGet();
                        return "fixture-observation-ready";
                    });
            try (ModelBackend backend = new LlamaCppJniBackend(Path.of(args[0]), model,
                    4096, 2, 0, "cpu")) {
                AgentSession.Result result = new AgentSession(backend, List.of(tool),
                        new AgentSession.Limits(3, 256, 16384, 1024,
                                Duration.ofSeconds(10))).run("fixture-agent-contract नमस्ते 🦙");
                if (!result.answer().equals("native agent fixture completed")
                        || !result.backendId().equals("llama.cpp-jni")
                        || result.modelCalls() != 2 || result.toolCalls() != 1
                        || calls.get() != 1 || result.trace().size() != 2) {
                    throw new AssertionError("native agent protocol did not complete exactly once");
                }
                if (result.trace().get(0).promptSha256().equals(result.trace().get(1).promptSha256())) {
                    throw new AssertionError("tool observation was not bound into next native prompt");
                }
            }
        } finally {
            Files.deleteIfExists(model);
        }
        System.out.println("PASS native agent protocol: two JNI inferences, one tool, exact trace; synthetic fixture");
        run(Path.of(args[0]));
    }

    static void run(Path library) throws Exception {
        Path workspace = Files.createTempDirectory("chromellm-agent-jni-");
        Path model = workspace.resolve("synthetic-agent.gguf");
        try {
            Files.writeString(model, "synthetic agent fixture, not model weights");
            Files.writeString(workspace.resolve("source.txt"), "agent-jni-observation नमस्ते\n");
            try (var backend = new LlamaCppJniBackend(library, model, 4096, 2, 0, "cpu");
                 WorkspaceTools workspaceTools = new WorkspaceTools(workspace)) {
                ModelResponse first = backend.infer(new ModelRequest("CHROMELLM_AGENT_FIXTURE", 512, Duration.ofSeconds(5)));
                Object parsed = McpJson.parse(first.text());
                if (!(parsed instanceof Map<?, ?> plan) || !"plan".equals(plan.get("type"))) {
                    throw new AssertionError("native fixture must produce the agent plan through JNI");
                }
                AgentEngine.Result result = new AgentEngine(backend, workspaceTools.tools(), event -> { })
                        .run("CHROMELLM_AGENT_FIXTURE", 512, AgentEngine.Limits.defaults(Duration.ofSeconds(10)));
                if (!result.answer().equals("JNI agent result नमस्ते") || result.modelCalls() != 2 || result.toolCalls() != 1) {
                    throw new AssertionError("JNI agent must read the workspace and resume model inference");
                }
            }
            Path goal = workspace.resolve("goal.txt");
            Files.writeString(goal, "CHROMELLM_AGENT_FIXTURE");
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            PrintStream originalOut = System.out, originalErr = System.err;
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                System.setOut(out);
                System.setErr(err);
                StandaloneMain.main(new String[]{"--agent", "true", "--workspace", workspace.toString(),
                        "--prompt-file", goal.toString(), "--backend", "jni", "--jni-lib", library.toString(),
                        "--model", model.toString(), "--compute-backend", "cpu", "--max-tokens", "512"});
            } finally { System.setOut(originalOut); System.setErr(originalErr); }
            if (!stdout.toString(StandardCharsets.UTF_8).equals("JNI agent result नमस्ते")) {
                throw new AssertionError("CLI final output was missing or mixed with progress");
            }
            String progress = stderr.toString(StandardCharsets.UTF_8);
            if (!progress.contains("tool_start workspace.read") || !progress.contains("complete")
                    || progress.contains("agent-jni-observation")) {
                throw new AssertionError("CLI progress must contain ordered metadata, not source contents");
            }
        } finally {
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("JNI agent engine/CLI contract checks passed (synthetic model)");
    }
}
