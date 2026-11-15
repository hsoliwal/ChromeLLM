// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Lazy, checksum-pinned model acquisition owned by the native host, independent of a browser. */
final class ModelArtifactCache {
    static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;
    private static final Duration ACQUISITION_TIMEOUT = Duration.ofHours(1);
    // Only active acquisitions retain a gate; unrelated model identities never share a lock.
    private static final ArrayList<Gate> GATES = new ArrayList<>();

    enum State {
        UNPREPARED, WAITING, VERIFYING, DOWNLOADING, AVAILABLE,
        INITIALIZING, READY, FAILED, CANCELLED, CLOSED
    }

    /** Immutable observation of the latest attempt; reading it performs no I/O. */
    record Progress(State state, long bytes) { }

    @FunctionalInterface
    interface Downloader {
        InputStream open(URI uri) throws IOException, InterruptedException;
    }

    private final URI url;
    private final String sha256;
    private final Path directory;
    private final Downloader downloader;
    private final long maxBytes;
    private final Duration acquisitionTimeout;
    private volatile Attempt latest;
    private static final Progress UNPREPARED = new Progress(State.UNPREPARED, 0);

    ModelArtifactCache(String url, String sha256, Path directory) {
        this(url, sha256, directory, ModelArtifactCache::fetch, MAX_BYTES);
    }

    /** Package-private transport seam; synthetic tests do not acquire licensed model data. */
    ModelArtifactCache(String rawUrl, String rawSha256, Path cache, Downloader downloader, long maxBytes) {
        this(rawUrl, rawSha256, cache, downloader, maxBytes, ACQUISITION_TIMEOUT);
    }

    ModelArtifactCache(String rawUrl, String rawSha256, Path cache, Downloader downloader,
                       long maxBytes, Duration acquisitionTimeout) {
        Objects.requireNonNull(rawUrl, "url");
        this.url = URI.create(rawUrl);
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getHost().isBlank() || url.getRawUserInfo() != null
                || url.getRawFragment() != null || url.getPort() == 0) {
            throw new IllegalArgumentException("model URL must be an HTTPS URL without credentials or fragment");
        }
        if (rawSha256 == null || !rawSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("model SHA-256 must contain 64 hex digits");
        }
        this.sha256 = rawSha256.toLowerCase(Locale.ROOT);
        this.directory = Objects.requireNonNull(cache, "cache directory").toAbsolutePath().normalize();
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        if (maxBytes < 1 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("invalid artifact byte bound");
        }
        this.maxBytes = maxBytes;
        this.acquisitionTimeout = Objects.requireNonNull(acquisitionTimeout, "acquisition timeout");
        if (acquisitionTimeout.isZero() || acquisitionTimeout.isNegative()
                || acquisitionTimeout.compareTo(ACQUISITION_TIMEOUT) > 0) {
            throw new IllegalArgumentException("acquisition timeout must be in (0, 1 hour]");
        }
    }

    Progress progress() {
        Attempt attempt = latest;
        return attempt == null ? UNPREPARED : attempt.progress;
    }

    Path resolve() throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model acquisition interrupted");
        long deadline = System.nanoTime() + acquisitionTimeout.toNanos();
        Attempt attempt = new Attempt();
        latest = attempt;
        attempt.observe(State.WAITING, 0);
        FutureTask<Path> result = new FutureTask<>(() -> acquire(attempt));
        attempt.worker = Thread.ofVirtual().name("chromellm-model-acquisition").unstarted(result);
        attempt.worker.start();
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("model acquisition deadline elapsed");
            return result.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            attempt.cancel(State.FAILED);
            HttpTimeoutException failure = new HttpTimeoutException("model acquisition exceeded its deadline");
            failure.initCause(timeout);
            throw failure;
        } catch (InterruptedException interrupted) {
            attempt.cancel(State.CANCELLED);
            throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException interrupted) throw interrupted;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("model acquisition failed", cause);
        }
    }

    private Path acquire(Attempt attempt) throws IOException, InterruptedException {
        try {
            attempt.check();
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isWritable(directory)) {
                throw new IOException("model cache directory must exist and be writable");
            }
            Path target = directory.toRealPath().resolve(sha256 + ".gguf");
            Gate gate = retain(target);
            boolean locked = false;
            try {
                gate.lock.lockInterruptibly();
                locked = true;
                attempt.check();
                // Recheck after waiting: another instance may already have supplied the artifact.
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    verify(target, attempt);
                    attempt.observe(State.AVAILABLE, Files.size(target));
                    return target;
                }
                return download(target, attempt);
            } finally {
                if (locked) gate.lock.unlock();
                release(gate);
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            attempt.observe(failure instanceof InterruptedException ? State.CANCELLED : State.FAILED,
                    attempt.progress.bytes());
            throw failure;
        }
    }

    private Path download(Path target, Attempt attempt) throws IOException, InterruptedException {
        try (Stage staged = new Stage(target.getParent())) {
            Path stage = staged.path;
            MessageDigest digest = digest();
            long count = 0;
            attempt.observe(State.DOWNLOADING, 0);
            try (InputStream source = attempt.attach(downloader.open(url));
                    OutputStream output = Files.newOutputStream(stage,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    attempt.check();
                    if (read > maxBytes - count) throw new IOException("model artifact exceeds byte bound");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    count += read;
                    attempt.observe(State.DOWNLOADING, count);
                }
            }
            attempt.check();
            if (count == 0 || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(sha256))) {
                throw new IOException("model artifact SHA-256 mismatch or empty download");
            }
            // This check admits one verified publication; no filesystem call holds a cancellation lock.
            attempt.check();
            try {
                Files.createLink(target, stage);
            } catch (FileAlreadyExistsException concurrent) {
                // Another process may have published; its bytes must pass the same verifier below.
            }
            verify(target, attempt);
            attempt.observe(State.AVAILABLE, count);
            return target;
        }
    }

    private static final class Stage implements AutoCloseable {
        final Path path;

        Stage(Path directory) throws IOException {
            path = Files.createTempFile(directory, ".chromellm-model-", ".part");
        }

        @Override
        public void close() throws IOException { Files.deleteIfExists(path); }
    }

    private void verify(Path target, Attempt attempt) throws IOException, InterruptedException {
        attempt.check();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cached model is not a regular file");
        }
        MessageDigest digest = digest();
        long size = 0;
        attempt.observe(State.VERIFYING, 0);
        try (InputStream input = attempt.attach(Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                attempt.check();
                if (read > maxBytes - size) throw new IOException("cached model exceeds byte bound");
                digest.update(buffer, 0, read);
                size += read;
                attempt.observe(State.VERIFYING, size);
            }
        }
        attempt.check();
        if (size == 0 || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(sha256))) {
            throw new IOException("cached model SHA-256 mismatch");
        }
    }

    private static Gate retain(Path target) {
        synchronized (GATES) {
            for (Gate gate : GATES) {
                if (gate.target.equals(target)) {
                    gate.users++;
                    return gate;
                }
            }
            Gate gate = new Gate(target);
            GATES.add(gate);
            return gate;
        }
    }

    private static void release(Gate gate) {
        synchronized (GATES) {
            if (--gate.users == 0) GATES.remove(gate);
        }
    }

    private static final class Gate {
        final Path target;
        final ReentrantLock lock = new ReentrantLock();
        int users = 1;

        Gate(Path target) { this.target = target; }
    }

    private final class Attempt {
        volatile Thread worker;
        volatile Progress progress = UNPREPARED;
        private boolean cancelled;
        private ClosingInput input;

        synchronized void check() throws InterruptedException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("model acquisition interrupted");
            }
        }

        synchronized void observe(State state, long bytes) {
            if (!cancelled) progress = new Progress(state, bytes);
        }

        InputStream attach(InputStream source) throws IOException, InterruptedException {
            ClosingInput attached = new ClosingInput(Objects.requireNonNull(source, "download stream"));
            synchronized (this) {
                if (!cancelled && !Thread.currentThread().isInterrupted()) {
                    input = attached;
                    return attached;
                }
            }
            attached.close();
            throw new InterruptedException("model acquisition interrupted");
        }

        void cancel(State state) {
            ClosingInput closing;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                progress = new Progress(state, progress.bytes());
                closing = input;
                worker.interrupt();
            }
            if (closing != null) {
                // A close implementation must not hold the caller beyond its acquisition deadline.
                Thread.ofVirtual().name("chromellm-model-cancel").start(() -> {
                    try { closing.close(); } catch (IOException ignored) { /* Worker retains cleanup authority. */ }
                });
            }
        }
    }

    private static final class ClosingInput extends FilterInputStream {
        private final AtomicBoolean closed = new AtomicBoolean();

        ClosingInput(InputStream source) { super(source); }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) super.close();
        }
    }

    private static InputStream fetch(URI url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
        try {
            InputStream source = fetch(url, client);
            return new FilterInputStream(source) {
                @Override
                public void close() throws IOException {
                    try { super.close(); } finally { client.shutdownNow(); }
                }
            };
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            client.shutdownNow();
            throw failure;
        }
    }

    static InputStream fetch(URI url, HttpClient client) throws IOException, InterruptedException {
        boolean releaseDownload = githubRelease(url);
        URI current = url;
        for (int redirects = 0; ; redirects++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(Duration.ofHours(1))
                    .header("Accept-Encoding", "identity").GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream input = response.body();
            int status = response.statusCode();
            URI next;
            try {
                if (status == 200) {
                    String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
                    long length;
                    try { length = response.headers().firstValueAsLong("Content-Length").orElse(-1); }
                    catch (NumberFormatException invalid) {
                        throw new IOException("invalid model artifact Content-Length", invalid);
                    }
                    if (!"identity".equalsIgnoreCase(encoding) || length < -1 || length > MAX_BYTES) {
                        throw new IOException("model artifact has unsupported encoding or size");
                    }
                    return input;
                }
                if (!releaseDownload || redirects >= 3
                        || (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)) {
                    throw new IOException("model download returned HTTP " + status + " or exceeded its redirect limit");
                }
                var locations = response.headers().allValues("Location");
                if (locations.size() != 1) throw new IOException("model redirect requires one Location header");
                try { next = current.resolve(locations.getFirst()); }
                catch (IllegalArgumentException malformed) { throw new IOException("invalid model redirect", malformed); }
                if (!httpsDefaultPort(next) || (!githubRelease(next)
                        && !"release-assets.githubusercontent.com".equalsIgnoreCase(next.getHost()))) {
                    throw new IOException("model redirect is outside GitHub release storage");
                }
            } catch (IOException | RuntimeException | Error failure) {
                try { input.close(); }
                catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
            // Close only the intermediate response. The owning fetch closes the client
            // after the final stream, or on failure; the same client handles the next hop.
            input.close();
            current = next;
        }
    }

    private static boolean githubRelease(URI url) {
        return httpsDefaultPort(url) && "github.com".equalsIgnoreCase(url.getHost())
                && url.getRawPath() != null
                && url.getRawPath().matches("/[^/]+/[^/]+/releases/download/[^/]+/[^/]+");
    }

    private static boolean httpsDefaultPort(URI url) {
        return "https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                && url.getRawUserInfo() == null && url.getRawFragment() == null
                && (url.getPort() == -1 || url.getPort() == 443);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }
}
