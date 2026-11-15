// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual Java/JNI agent wiring against the scripted C++ fixture; not trained-model inference. */
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
    }
}
