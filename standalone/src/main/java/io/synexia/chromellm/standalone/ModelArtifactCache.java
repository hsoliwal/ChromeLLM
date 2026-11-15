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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Lazy, checksum-pinned model acquisition owned by the native host, independent of a browser. */
final class ModelArtifactCache {
    static final long MAX_BYTES = 64L * 1024 * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
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

    /** Narrow HTTP test seam; callers of the cache still use the original Downloader contract. */
    interface Transport extends AutoCloseable {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
        @Override void close();
    }

    private final URI url;
    private final String sha256;
    private final Path directory;
    private final Downloader downloader;
    private final long maxBytes;
    private final long expectedBytes;
    private final String suffix;
    private final Duration acquisitionTimeout;
    private volatile Attempt latest;
    private static final Progress UNPREPARED = new Progress(State.UNPREPARED, 0);

    ModelArtifactCache(String url, String sha256, Path directory) {
        this(url, sha256, directory, ModelArtifactCache::fetch, MAX_BYTES);
    }

    /** Native archives/binaries require both an exact byte count and a SHA-256 pin. */
    ModelArtifactCache(String url, String sha256, Path directory, long expectedBytes) {
        this(url, sha256, directory, ModelArtifactCache::fetch, expectedBytes, expectedBytes, ".bin");
    }

    ModelArtifactCache(String rawUrl, String rawSha256, Path cache, Downloader downloader,
                       long maxBytes, long expectedBytes, String suffix) {
        this(rawUrl, rawSha256, cache, downloader, maxBytes, ACQUISITION_TIMEOUT, expectedBytes, suffix);
    }

    /** Package-private transport seam; synthetic tests do not acquire licensed model data. */
    ModelArtifactCache(String rawUrl, String rawSha256, Path cache, Downloader downloader, long maxBytes) {
        this(rawUrl, rawSha256, cache, downloader, maxBytes, ACQUISITION_TIMEOUT);
    }

    ModelArtifactCache(String rawUrl, String rawSha256, Path cache, Downloader downloader,
                       long maxBytes, Duration acquisitionTimeout) {
        this(rawUrl, rawSha256, cache, downloader, maxBytes, acquisitionTimeout, -1, ".gguf");
    }

    private ModelArtifactCache(String rawUrl, String rawSha256, Path cache, Downloader downloader,
                               long maxBytes, Duration acquisitionTimeout, long expectedBytes, String suffix) {
        this.url = validateUrl(URI.create(Objects.requireNonNull(rawUrl, "url")));
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
        if (expectedBytes != -1 && (expectedBytes < 1 || expectedBytes > maxBytes)) {
            throw new IllegalArgumentException("invalid expected artifact byte count");
        }
        if ((!".gguf".equals(suffix) && !".bin".equals(suffix))
                || (".bin".equals(suffix) && expectedBytes < 1)) {
            throw new IllegalArgumentException("binary artifacts require a size pin and supported suffix");
        }
        this.expectedBytes = expectedBytes;
        this.suffix = suffix;
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
        return resolveWithOfflinePolicy(false);
    }

    Path resolveWithOfflinePolicy(boolean offline) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model acquisition interrupted");
        long deadline = System.nanoTime() + acquisitionTimeout.toNanos();
        Attempt attempt = new Attempt();
        latest = attempt;
        attempt.observe(State.WAITING, 0);
        FutureTask<Path> result = new FutureTask<>(() -> acquire(attempt, offline));
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

    private Path acquire(Attempt attempt, boolean offline) throws IOException, InterruptedException {
        try {
            attempt.check();
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isWritable(directory)) {
                throw new IOException("model cache directory must exist and be writable");
            }
            Path target = directory.toRealPath().resolve(sha256 + suffix);
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
                if (offline) throw new IOException("artifact is missing from the verified offline cache");
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
            if (count == 0 || (expectedBytes >= 0 && count != expectedBytes)
                    || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(sha256))) {
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
        if (expectedBytes >= 0 && Files.size(target) != expectedBytes) {
            throw new IOException("cached artifact byte count mismatch");
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
        if (size == 0 || (expectedBytes >= 0 && size != expectedBytes)
                || !MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(sha256))) {
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
        return fetch(url, client);
    }

    /** HTTP compatibility seam; all requests share the owned transport and body lifecycle. */
    static InputStream fetch(URI url, HttpClient client) throws IOException, InterruptedException {
        Objects.requireNonNull(client, "HTTP client");
        return fetch(url, new Transport() {
            @Override public HttpResponse<InputStream> send(HttpRequest request)
                    throws IOException, InterruptedException {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            }
            @Override public void close() {
                // close() waits indefinitely for outstanding operations; abort them on cancellation.
                client.shutdownNow();
            }
        }, ACQUISITION_TIMEOUT);
    }

    static InputStream fetch(URI initialUrl, Transport transport, Duration timeout)
            throws IOException, InterruptedException {
        Objects.requireNonNull(transport, "transport");
        DownloadLifetime lifetime = new DownloadLifetime(transport, timeout);
        try {
            URI current = validateUrl(initialUrl);
            for (int redirects = 0; ; redirects++) {
                lifetime.check();
                HttpRequest request = HttpRequest.newBuilder(current).timeout(lifetime.remaining())
                        .header("Accept-Encoding", "identity").GET().build();
                HttpResponse<InputStream> response = transport.send(request);
                InputStream input = Objects.requireNonNull(response.body(), "response body");
                lifetime.attach(input);
                int status = response.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    lifetime.releaseBody();
                    if (redirects >= MAX_REDIRECTS) throw new IOException("artifact redirect limit exceeded");
                    var locations = response.headers().allValues("Location");
                    if (locations.size() != 1) throw new IOException("artifact redirect needs one Location header");
                    try { current = validateUrl(current.resolve(locations.getFirst())); }
                    catch (IllegalArgumentException invalid) {
                        throw new IOException("artifact redirect must preserve HTTPS without credentials or fragment", invalid);
                    }
                    continue;
                }
                if (status != 200) throw new IOException("artifact download returned HTTP " + status);
                var encodings = response.headers().allValues("Content-Encoding");
                long contentLength;
                try { contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1); }
                catch (NumberFormatException malformed) { throw new IOException("invalid artifact Content-Length", malformed); }
                if ((!encodings.isEmpty() && (encodings.size() != 1
                        || !"identity".equalsIgnoreCase(encodings.getFirst())))
                        || contentLength < -1 || contentLength > MAX_BYTES) {
                    throw new IOException("artifact has unsupported encoding or size");
                }
                return new FilterInputStream(input) {
                    private boolean closed;
                    @Override public int read() throws IOException {
                        byte[] one = new byte[1];
                        return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
                    }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        Objects.checkFromIndexSize(offset, length, bytes.length);
                        lifetime.check();
                        if (closed) throw new IOException("artifact download stream is closed");
                        if (length == 0) return 0;
                        try {
                            int result = in.read(bytes, offset, length);
                            lifetime.check();
                            if (result == -1) close();
                            return result;
                        } catch (IOException failure) {
                            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                            lifetime.check();
                            throw failure;
                        }
                    }
                    @Override public void close() throws IOException {
                        if (!closed) { closed = true; lifetime.close(); }
                    }
                };
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            try { lifetime.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static URI validateUrl(URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getHost().isBlank() || url.getRawUserInfo() != null
                || url.getRawFragment() != null || url.getPort() == 0 || url.getPort() > 65535) {
            throw new IllegalArgumentException("artifact URL must be HTTPS without credentials or fragment");
        }
        return url;
    }

    /** A single deadline covers every redirect, response header and streamed response byte. */
    private static final class DownloadLifetime implements AutoCloseable {
        private final Transport transport;
        private final long deadline;
        private final AtomicReference<InputStream> body = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread watchdog;
        private volatile boolean timedOut;

        DownloadLifetime(Transport transport, Duration timeout) {
            this.transport = transport;
            if (timeout == null || timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(ACQUISITION_TIMEOUT) > 0) {
                transport.close();
                throw new IllegalArgumentException("invalid artifact download timeout");
            }
            deadline = System.nanoTime() + timeout.toNanos();
            watchdog = Thread.ofVirtual().name("chromellm-artifact-deadline").unstarted(() -> {
                try {
                    long remaining;
                    while ((remaining = deadline - System.nanoTime()) > 0) {
                        Thread.sleep(Duration.ofNanos(remaining));
                    }
                    if (!closed.get()) {
                        timedOut = true;
                        try { close(); } catch (IOException ignored) { /* The reader reports timeout. */ }
                    }
                } catch (InterruptedException completed) { /* Closed before its deadline. */ }
            });
            watchdog.start();
        }

        void check() throws HttpTimeoutException {
            if (timedOut || deadline - System.nanoTime() <= 0) {
                timedOut = true;
                throw new HttpTimeoutException("artifact download exceeded its total deadline");
            }
        }

        Duration remaining() throws HttpTimeoutException {
            check();
            return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
        }

        void attach(InputStream input) throws IOException {
            body.set(input);
            if (closed.get()) { releaseBody(); throw new HttpTimeoutException("artifact download closed"); }
            check();
        }

        void releaseBody() throws IOException {
            InputStream input = body.getAndSet(null);
            if (input != null) input.close();
        }

        @Override public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                if (Thread.currentThread() != watchdog) watchdog.interrupt();
                // Abort a pending send/read before closing its body, so shutdown never waits for EOF.
                try { transport.close(); }
                finally { releaseBody(); }
            }
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("SHA-256 unavailable", impossibleOnJava21);
        }
    }
}
