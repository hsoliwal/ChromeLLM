// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Explicit tier-to-provider routing with no model substitution or retry. Owns provided backends. */
public final class TieredInference implements AutoCloseable {
    private final EnumMap<InferenceTier, ModelBackend> backends = new EnumMap<>(InferenceTier.class);
    private final ReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;

    public TieredInference(Map<InferenceTier, ? extends ModelBackend> routes) {
        Objects.requireNonNull(routes, "routes");
        if (routes.isEmpty()) throw new IllegalArgumentException("at least one explicit tier route is required");
        for (Map.Entry<InferenceTier, ? extends ModelBackend> route : routes.entrySet()) {
            InferenceTier tier = Objects.requireNonNull(route.getKey(), "tier");
            ModelBackend backend = Objects.requireNonNull(route.getValue(), "backend");
            if (backend.id() == null || backend.id().isBlank()) {
                throw new IllegalArgumentException("backend ID must not be blank");
            }
            backends.put(tier, backend);
        }
    }

    /** A missing tier, provider failure, or mismatched receipt is a hard failure: never fall back. */
    public ModelResponse infer(InferenceTier tier, ModelRequest request)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(request, "request");
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IOException("inference router is closed");
            ModelBackend backend = backends.get(tier);
            if (backend == null) throw new IOException("no provider configured for tier " + tier);
            ModelResponse response = backend.infer(request);
            if (response == null || !backend.id().equals(response.backendId()) ||
                    !MessageDigest.isEqual(sha256(request.prompt()), hexToBytes(response.promptSha256()))) {
                throw new IOException("provider returned an invalid backend identity or prompt receipt");
            }
            return response;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            IdentityHashMap<ModelBackend, Boolean> seen = new IdentityHashMap<>();
            for (InferenceTier tier : InferenceTier.values()) {
                ModelBackend backend = backends.get(tier);
                if (backend != null && seen.put(backend, Boolean.TRUE) == null) backend.close();
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private static byte[] sha256(String prompt) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }

    private static byte[] hexToBytes(String hex) {
        // The ModelResponse record already validates lower-case 64-character SHA-256.
        return java.util.HexFormat.of().parseHex(hex);
    }
}
