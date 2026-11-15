// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone.consumer;

import io.synexia.chromellm.standalone.LlamaCppJniBackend;
import io.synexia.chromellm.standalone.ModelRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Independent public-API consumer, launched from its own JAR by the packaged JNI smoke test. */
public final class PackagedRuntimeConsumer {
    private PackagedRuntimeConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("true") || args[0].equals("false"))) {
            throw new IllegalArgumentException("pass the explicit synthetic test flag");
        }
        if (LlamaCppJniBackend.availableDevices().isEmpty()) {
            throw new AssertionError("packaged Java API returned no registered device");
        }
        if (Boolean.parseBoolean(args[0])) {
            Path fixture = Files.createTempFile("require-default-packaged-fixture-", ".gguf");
            try {
                Files.writeString(fixture, "synthetic fixture, not model weights");
                try (LlamaCppJniBackend backend = LlamaCppJniBackend.fromPackagedRuntime(fixture, 1024, 2, 0)) {
                    var response = backend.infer(new ModelRequest("packaged-factory", 8, Duration.ofSeconds(5)));
                    if (!response.text().equals("OK") || !backend.id().equals("llama.cpp-jni")) {
                        throw new AssertionError("packaged factory changed the existing native provider result");
                    }
                }
            } finally {
                Files.deleteIfExists(fixture);
            }
        }
        System.out.println("PACKAGED_API_READY " + (Boolean.parseBoolean(args[0])
                ? "synthetic factory inference and device enumeration" : "real device enumeration only"));
    }
}
