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
    private final Object lifecycle = new Object();
    private ModelBackend loaded;
    private Thread active;
    private boolean preparing;
    private boolean opening;
    private boolean initializationFailed;
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

    ModelArtifactCache.Progress progress() {
        synchronized (lifecycle) {
            ModelArtifactCache.Progress artifact = cache.progress();
            ModelArtifactCache.State state = closed ? ModelArtifactCache.State.CLOSED
                    : loaded != null ? ModelArtifactCache.State.READY
                    : opening ? ModelArtifactCache.State.INITIALIZING
                    : initializationFailed ? ModelArtifactCache.State.FAILED : artifact.state();
            return new ModelArtifactCache.Progress(state, artifact.bytes());
        }
    }

    @Override
    public ModelResponse infer(ModelRequest request)
            throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(request, "request");
        synchronized (lifecycle) {
            while (active != null && !closed) lifecycle.wait();
            requireOpen();
            active = Thread.currentThread();
            preparing = loaded == null;
            initializationFailed = false;
        }
        Throwable primaryFailure = null;
        try {
            ModelBackend backend;
            synchronized (lifecycle) { backend = loaded; }
            if (backend == null) {
                Path verified = cache.resolve();
                synchronized (lifecycle) {
                    requireOpen();
                    opening = true;
                }
                try {
                    ModelBackend candidate = Objects.requireNonNull(opener.open(verified), "opened backend");
                    boolean installed = false;
                    Throwable admissionFailure = null;
                    try {
                        if (!backendId.equals(candidate.id())) throw new IOException("lazy backend identity changed");
                        synchronized (lifecycle) {
                            requireOpen();
                            loaded = candidate;
                            installed = true;
                        }
                        backend = candidate;
                    } catch (IOException | RuntimeException | Error failure) {
                        admissionFailure = failure;
                        throw failure;
                    } finally {
                        // Closing during a native open never leaks its eventual returned provider.
                        if (!installed) closePreservingFailure(candidate, admissionFailure);
                    }
                } catch (IOException | InterruptedException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IOException("local model initialization failed", failure);
                } finally {
                    synchronized (lifecycle) {
                        opening = false;
                        initializationFailed = loaded == null && !closed;
                    }
                }
            }
            synchronized (lifecycle) {
                requireOpen();
                // The provider now owns the admitted call; close defers disposal until it unwinds.
                preparing = false;
            }
            return backend.infer(request);
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            ModelBackend closing = null;
            synchronized (lifecycle) {
                active = null;
                preparing = false;
                opening = false;
                if (closed) {
                    closing = loaded;
                    loaded = null;
                }
                lifecycle.notifyAll();
            }
            if (closing != null) closePreservingFailure(closing, primaryFailure);
        }
    }

    private static void closePreservingFailure(ModelBackend backend, Throwable primary) {
        try { backend.close(); }
        catch (RuntimeException | Error cleanup) {
            if (primary == null) throw cleanup;
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("lazy model backend is closed");
    }

    @Override
    public void close() {
        ModelBackend closing = null;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            if (active == null) {
                closing = loaded;
                loaded = null;
            } else if (preparing) {
                // Interrupt while the active-owner identity is locked, before it can be reused.
                active.interrupt();
            }
            lifecycle.notifyAll();
        }
        if (closing != null) closing.close();
    }
}
