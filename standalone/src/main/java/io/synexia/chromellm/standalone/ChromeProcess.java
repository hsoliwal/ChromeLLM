// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.chromellm.standalone;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one Chrome process, dedicated locked profile, and fixed loopback AI document. */
final class ChromeProcess implements AutoCloseable {
    private static final String MARKER = "ChromeLLM dedicated Chrome AI profile v1\n";
    private static final int DISCOVERY_BYTES = 128 * 1024;
    private final FileChannel lockChannel;
    private final FileLock profileLock;
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final String authority;
    private final String route;
    private final URI page;
    private final byte[] script;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private Process process;
    private Thread shutdownHook;
    private volatile Thread cleanupThread;
    private URI endpoint;
    private String version;

    private ChromeProcess(FileChannel channel, FileLock lock) throws IOException {
        lockChannel = channel;
        profileLock = lock;
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        route = "/" + HexFormat.of().formatHex(nonce) + "/";
        script = StdioProtocol.encode(ChromeAiScript.source(), 256 * 1024);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().daemon(true).name("chromellm-page-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        HttpServer host;
        try {
            host = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 8);
        } catch (IOException | RuntimeException failure) { workers.shutdownNow(); throw failure; }
        server = host;
        authority = "127.0.0.1:" + server.getAddress().getPort();
        page = URI.create("http://" + authority + route);
        server.setExecutor(workers);
        server.createContext("/", this::serve);
        try { server.start(); }
        catch (RuntimeException failure) { server.stop(0); workers.shutdownNow(); throw failure; }
    }

    static ChromeProcess start(Path executable, Path requestedProfile, boolean headless, long deadlineNanos)
            throws IOException, InterruptedException, TimeoutException {
        remaining(deadlineNanos);
        Path binary = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
            throw new IOException("Chrome executable is not an executable regular file");
        }
        Path profile = dedicatedProfile(requestedProfile);
        Path lockPath = profile.resolve(".chromellm-profile.lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        ChromeProcess owner = null;
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Chrome profile is already in use", busy); }
            if (lock == null) throw new IOException("Chrome profile is already in use");
            admitProfile(profile);
            remaining(deadlineNanos);
            Path activePort = profile.resolve("DevToolsActivePort");
            if (Files.isSymbolicLink(activePort)) throw new IOException("Chrome discovery file must not be a symbolic link");
            Files.deleteIfExists(activePort);
            owner = new ChromeProcess(channel, lock);
            List<String> command = new ArrayList<>(List.of(binary.toString(),
                    "--user-data-dir=" + profile,
                    "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
                    "--no-first-run", "--no-default-browser-check", "--disable-sync"));
            if (headless) command.add("--headless=new");
            command.add(owner.page.toString());
            owner.process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            ChromeProcess launched = owner;
            owner.shutdownHook = Thread.ofPlatform().name("chromellm-process-shutdown").unstarted(launched::shutdown);
            Runtime.getRuntime().addShutdownHook(owner.shutdownHook);
            owner.discover(activePort, deadlineNanos);
            return owner;
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException failure) {
            if (owner != null) owner.close();
            else {
                if (lock != null) try { lock.release(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                try { channel.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    URI endpoint() { return endpoint; }
    String version() { return version; }

    private static Path dedicatedProfile(Path input) throws IOException {
        Path path = Objects.requireNonNull(input, "profile").toAbsolutePath().normalize();
        if (path.getParent() == null) throw new IOException("A dedicated Chrome profile directory is required");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        List<Path> personal = new ArrayList<>(List.of(home.resolve(".config/google-chrome"),
                home.resolve(".config/google-chrome-beta"), home.resolve(".config/google-chrome-unstable"),
                home.resolve(".config/chromium"), home.resolve("Library/Application Support/Google/Chrome"),
                home.resolve("Library/Application Support/Chromium")));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            personal.add(Path.of(local, "Google", "Chrome", "User Data").toAbsolutePath().normalize());
            personal.add(Path.of(local, "Chromium", "User Data").toAbsolutePath().normalize());
            personal.add(Path.of(local, "Microsoft", "Edge", "User Data").toAbsolutePath().normalize());
        }
        if (path.equals(home) || personal.stream().anyMatch(path::startsWith)) {
            throw new IOException("Personal browser profiles are not permitted; choose a dedicated empty directory");
        }
        Path component = path.getRoot();
        for (Path name : path) {
            component = component.resolve(name);
            if (Files.isSymbolicLink(component)) throw new IOException("Chrome profile path must not contain symbolic links");
        }
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Chrome profile must be a directory");
        Path real = path.toRealPath();
        if (!Files.exists(real.resolve(".chromellm-profile"), LinkOption.NOFOLLOW_LINKS)) {
            // Refuse unrelated data before creating even our lock file. Recheck after locking below.
            try (var entries = Files.list(real)) {
                if (entries.anyMatch(entry -> !entry.getFileName().toString().equals(".chromellm-profile.lock"))) {
                    throw new IOException("Chrome profile must be empty before its first ChromeLLM use");
                }
            }
        }
        return real;
    }

    private static void admitProfile(Path profile) throws IOException {
        Path marker = profile.resolve(".chromellm-profile");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !new String(readBounded(marker, 128), StandardCharsets.UTF_8).equals(MARKER)) {
                throw new IOException("Chrome profile has an invalid ownership marker");
            }
            return;
        }
        try (var entries = Files.list(profile)) {
            if (entries.anyMatch(path -> !path.getFileName().toString().equals(".chromellm-profile.lock"))) {
                throw new IOException("Chrome profile must be empty before its first ChromeLLM use");
            }
        }
        Files.writeString(marker, MARKER, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void discover(Path activePort, long deadline) throws IOException, InterruptedException, TimeoutException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI ignored) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
        try {
            URI origin = null;
            String browserPath = null;
            while (true) {
                alive(deadline);
                if (origin == null && Files.exists(activePort, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(activePort, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Unsafe Chrome discovery file");
                    }
                    String[] lines = new String(readBounded(activePort, 4096), StandardCharsets.US_ASCII).split("\\R");
                    if (lines.length >= 2) {
                        int port;
                        try { port = Integer.parseInt(lines[0]); }
                        catch (NumberFormatException malformed) { throw new IOException("Invalid Chrome debugging port", malformed); }
                        if (port < 1 || port > 65535 || !lines[1].matches("/devtools/browser/[A-Za-z0-9_-]{1,200}")) {
                            throw new IOException("Invalid Chrome discovery endpoint");
                        }
                        origin = URI.create("http://127.0.0.1:" + port);
                        browserPath = lines[1];
                    }
                }
                if (origin != null) {
                    try {
                        Map<?, ?> info = object(json(http, origin.resolve("/json/version"), deadline));
                        URI browser = websocket(info.get("webSocketDebuggerUrl"), origin, browserPath);
                        if (browser == null) throw new IOException("Chrome browser endpoint identity mismatch");
                        Object observed = info.get("Browser");
                        if (!(observed instanceof String text) || text.isBlank() || text.length() > 256
                                || text.chars().anyMatch(Character::isISOControl)) {
                            throw new IOException("Chrome version metadata is invalid");
                        }
                        Object listing = json(http, origin.resolve("/json/list"), deadline);
                        if (!(listing instanceof List<?> targets)) throw new IOException("Chrome target list is invalid");
                        URI found = null;
                        for (Object raw : targets) {
                            Map<?, ?> target = object(raw);
                            if (!"page".equals(target.get("type")) || !page.toString().equals(target.get("url"))) continue;
                            Object id = target.get("id");
                            if (!(id instanceof String name) || !name.matches("[A-Za-z0-9_-]{1,200}")) {
                                throw new IOException("Chrome page identity is invalid");
                            }
                            URI candidate = websocket(target.get("webSocketDebuggerUrl"), origin, "/devtools/page/" + name);
                            if (candidate == null || found != null) throw new IOException("Chrome page endpoint identity mismatch");
                            found = candidate;
                        }
                        if (found != null) { alive(deadline); endpoint = found; version = text; return; }
                    } catch (ConnectException notListeningYet) { /* Chrome can publish its port just before listening. */ }
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining(deadline), TimeUnit.MILLISECONDS.toNanos(25)));
            }
        } finally { http.shutdownNow(); }
    }

    private static URI websocket(Object input, URI origin, String path) {
        if (!(input instanceof String value) || value.length() > 1024) return null;
        try {
            URI uri = URI.create(value);
            return "ws".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() == origin.getPort() && path.equals(uri.getRawPath())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null ? uri : null;
        } catch (IllegalArgumentException malformed) { return null; }
    }

    private static Map<?, ?> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("Chrome discovery object is invalid");
        return map;
    }

    private static Object json(HttpClient http, URI uri, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        BoundedBody body = new BoundedBody();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining(deadline))).GET().build();
        CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(request, ignored -> body);
        try {
            HttpResponse<byte[]> response = exchange.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new IOException("Chrome discovery returned HTTP " + response.statusCode());
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            return McpJson.parse(text);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException("Chrome discovery failed", failed.getCause());
        } finally { body.cancel(); exchange.cancel(true); }
    }

    private void alive(long deadline) throws IOException, InterruptedException, TimeoutException {
        remaining(deadline);
        if (closed.get() || !process.isAlive()) throw new IOException("Owned Chrome process exited before its bridge became ready");
    }

    private static long remaining(long deadline) throws InterruptedException, TimeoutException {
        if (Thread.interrupted()) throw new InterruptedException("Chrome launch interrupted");
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("Chrome launch deadline exceeded");
        return nanos;
    }

    private static byte[] readBounded(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Chrome profile metadata exceeds its limit");
            return bytes;
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Cross-Origin-Resource-Policy", "same-origin");
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'none'; "
                    + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
            headers.set("Permissions-Policy", "language-model=(self), camera=(), microphone=(), geolocation=()");
            var incoming = exchange.getRequestHeaders();
            if (!List.of(authority).equals(incoming.get("Host"))
                    || incoming.containsKey("Origin") && !List.of("http://" + authority).equals(incoming.get("Origin"))) {
                reply(exchange, 403, "text/plain", "Origin refused".getBytes(StandardCharsets.US_ASCII)); return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 405, "text/plain", "GET required".getBytes(StandardCharsets.US_ASCII)); return;
            }
            if (incoming.containsKey("Transfer-Encoding") || incoming.containsKey("Content-Length")
                    && !List.of("0").equals(incoming.get("Content-Length"))) {
                reply(exchange, 400, "text/plain", "Request body refused".getBytes(StandardCharsets.US_ASCII)); return;
            }
            URI uri = exchange.getRequestURI();
            if (uri.isAbsolute() || uri.getRawQuery() != null) {
                reply(exchange, 404, "text/plain", new byte[0]); return;
            }
            String path = uri.getRawPath();
            if (path.equals(route)) {
                String html = "<!doctype html><meta charset=\"utf-8\"><title>ChromeLLM built-in AI</title>"
                        + "<h1>ChromeLLM built-in AI</h1><p>This is the dedicated ChromeLLM profile.</p>"
                        + "<button id=\"inspect\">Check availability</button> <button id=\"prepare\">Prepare model</button>"
                        + "<pre id=\"status\">Ready for the Java caller. Prepare explicitly authorizes model provisioning.</pre>"
                        + "<script src=\"bridge.js\"></script><script src=\"page.js\" defer></script>";
                reply(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
            } else if (path.equals(route + "bridge.js")) {
                reply(exchange, 200, "text/javascript; charset=utf-8", script);
            } else if (path.equals(route + "page.js")) {
                String controller = "'use strict';const out=document.getElementById('status');"
                        + "document.getElementById('inspect').onclick=async()=>{try{out.textContent=JSON.stringify(await chromeLlm.availability());}catch(e){out.textContent=String(e);}};"
                        + "document.getElementById('prepare').onclick=async()=>{try{out.textContent='Preparing model...';out.textContent=JSON.stringify(await chromeLlm.prepare(true));}catch(e){out.textContent=String(e);}};";
                reply(exchange, 200, "text/javascript; charset=utf-8", controller.getBytes(StandardCharsets.UTF_8));
            } else reply(exchange, 404, "text/plain", new byte[0]);
        }
    }

    private static void reply(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }

    /** Return immediately; retain the profile lock until the owned process and observed descendants exit. */
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        workers.shutdownNow();
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException shuttingDown) { /* The registered hook performs bounded cleanup below. */ }
        }
        Process owned = process;
        if (owned == null) { release(); return; }
        cleanupThread = Thread.ofPlatform().daemon(false).name("chromellm-process-cleanup").unstarted(() -> {
            List<ProcessHandle> children;
            try (var descendants = owned.descendants()) { children = descendants.limit(256).toList(); }
            catch (RuntimeException unavailable) { children = List.of(); }
            try {
                children.forEach(ProcessHandle::destroy);
                owned.destroy();
                if (!owned.waitFor(500, TimeUnit.MILLISECONDS)) owned.destroyForcibly();
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                owned.waitFor(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); owned.destroyForcibly();
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            } finally {
                List<CompletableFuture<?>> exits = new ArrayList<>();
                if (owned.isAlive()) exits.add(owned.onExit());
                for (ProcessHandle child : children) if (child.isAlive()) exits.add(child.onExit());
                CompletableFuture.allOf(exits.toArray(CompletableFuture<?>[]::new)).whenComplete((ignored, failure) -> release());
            }
        });
        cleanupThread.start();
    }

    private void shutdown() {
        close();
        Thread cleanup = cleanupThread;
        try { if (cleanup != null) cleanup.join(2500); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }

    private void release() {
        if (!released.compareAndSet(false, true)) return;
        try { profileLock.release(); } catch (IOException ignored) { /* Closing the channel also releases its lock. */ }
        try { lockChannel.close(); } catch (IOException ignored) { /* close() is best effort after process retirement. */ }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final byte[] bytes = new byte[DISCOVERY_BYTES];
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int used;
        @Override public CompletionStage<byte[]> getBody() { return completed; }
        @Override public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null || completed.isDone()) { incoming.cancel(); return; }
            subscription = incoming; incoming.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (completed.isDone()) return;
            for (ByteBuffer data : buffers) {
                if (data.remaining() > bytes.length - used) {
                    completed.completeExceptionally(new IOException("Chrome discovery response exceeds its limit"));
                    subscription.cancel(); return;
                }
                int count = data.remaining(); data.get(bytes, used, count); used += count;
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { completed.completeExceptionally(error); }
        @Override public synchronized void onComplete() { completed.complete(Arrays.copyOf(bytes, used)); }
        synchronized void cancel() { if (subscription != null) subscription.cancel(); if (!completed.isDone()) completed.cancel(false); }
    }
}
