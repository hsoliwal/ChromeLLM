// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Offline tier dispatch and receipt checks; no model weights, device, or network required. */
public final class TieredInferenceTestMain {
    private static int checks;
    private TieredInferenceTestMain() { }

    public static void main(String[] args) throws Exception {
        check(InferenceTier.parse("fast") == InferenceTier.FAST);
        check(InferenceTier.parse("reasoning") == InferenceTier.REASONING);
        check(InferenceTier.parse("frontier") == InferenceTier.FRONTIER);
        reject(() -> InferenceTier.parse("AUTO"));
        reject(() -> InferenceTier.parse(null));
        reject(() -> new TieredInference(Map.of()));
        FakeModel fast = new FakeModel("fast-model");
        FakeModel deep = new FakeModel("deep-model");
        FakeModel frontier = new FakeModel("frontier-model");
        ModelRequest request = new ModelRequest("Explain contracts", 16, Duration.ofSeconds(2));
        try (TieredInference routes = new TieredInference(Map.of(
                InferenceTier.FAST, fast, InferenceTier.REASONING, deep,
                InferenceTier.FRONTIER, frontier))) {
            check(routes.infer(InferenceTier.FAST, request).text().equals("fast-model answer"));
            check(routes.infer(InferenceTier.REASONING, request).text().equals("deep-model answer"));
            check(routes.infer(InferenceTier.FRONTIER, request).text().equals("frontier-model answer"));
            check(fast.calls == 1 && deep.calls == 1 && frontier.calls == 1);
            fast.fail = true;
            try {
                routes.infer(InferenceTier.FAST, request);
                throw new AssertionError("provider failure was hidden");
            } catch (IOException expected) { checks++; }
            check(deep.calls == 1 && frontier.calls == 1); // No fallback or retry
            fast.fail = false;
            fast.badHash = true;
            try {
                routes.infer(InferenceTier.FAST, request);
                throw new AssertionError("unbound prompt receipt accepted");
            } catch (IOException expected) { checks++; }
            fast.badHash = false;
            fast.badIdentity = true;
            try {
                routes.infer(InferenceTier.FAST, request);
                throw new AssertionError("backend mismatch accepted");
            } catch (IOException expected) { checks++; }
        }
        check(fast.closes == 1 && deep.closes == 1 && frontier.closes == 1);
        FakeModel shared = new FakeModel("shared");
        TieredInference partial = new TieredInference(Map.of(
                InferenceTier.FAST, shared, InferenceTier.REASONING, shared));
        try {
            partial.infer(InferenceTier.FRONTIER, request);
            throw new AssertionError("missing tier silently substituted");
        } catch (IOException expected) { checks++; }
        partial.close();
        partial.close();
        check(shared.closes == 1);
        try {
            partial.infer(InferenceTier.FAST, request);
            throw new AssertionError("closed router allowed inference");
        } catch (IOException expected) { checks++; }
        System.out.println("tiered inference contract checks passed: " + checks);
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("tiered check " + checks);
    }
    private static void reject(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid route accepted");
        } catch (IllegalArgumentException expected) { checks++; }
    }
    private static final class FakeModel implements ModelBackend {
        private final String name;
        int calls;
        int closes;
        boolean fail;
        boolean badHash;
        boolean badIdentity;
        FakeModel(String name) { this.name = name; }
        @Override public String id() { return name; }
        @Override public ModelResponse infer(ModelRequest request) throws IOException, InterruptedException, TimeoutException {
            calls++;
            if (fail) throw new IOException("synthetic failure");
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((badHash ? "different" : request.prompt()).getBytes(StandardCharsets.UTF_8)));
                return new ModelResponse(name + " answer", badIdentity ? "other" : name, hash);
            } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
        @Override public void close() { closes++; }
    }
}
