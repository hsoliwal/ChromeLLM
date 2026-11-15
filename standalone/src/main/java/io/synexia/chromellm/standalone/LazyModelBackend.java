// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/** Defers one explicit local provider's model acquisition until the first inference. */
final class LazyModelBackend implements ModelBackend {
    @FunctionalInterface
    interface Opener {
        ModelBackend open(Path verifiedModel) throws Exception;
    }

    private final String backendId;
    private final ModelArtifactCache cache;
    private final Opener opener;
    private ModelBackend loaded;
    private boolean closed;

    LazyModelBackend(String backendId, ModelArtifactCache cache, Opener opener) {
        if (!"llama.cpp-cli".equals(backendId) && !"llama.cpp-jni".equals(backendId)) {
            throw new IllegalArgumentException("unsupported lazy local backend");
        }
        this.backendId = backendId;
        this.cache = Objects.requireNonNull(cache, "cache");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public String id() { return backendId; }

    @Override
    public synchronized ModelResponse infer(ModelRequest request)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        if (closed) throw new IOException("lazy model backend is closed");
        if (loaded == null) {
            Path verified = cache.resolve();
            try {
                ModelBackend candidate = Objects.requireNonNull(opener.open(verified), "opened backend");
                if (!backendId.equals(candidate.id())) {
                    candidate.close();
                    throw new IOException("lazy backend identity changed");
                }
                loaded = candidate;
            } catch (IOException | InterruptedException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("local model initialization failed", failure);
            }
        }
        return loaded.infer(request);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (loaded != null) loaded.close();
    }
}
