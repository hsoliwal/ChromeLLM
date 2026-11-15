// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.chromellm.standalone;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real process, profile-lock and loopback-host contracts without a browser or model download. */
public final class ChromeProcessContractTestMain {
    private static int checks;
    private static Class<?> processType;

    private ChromeProcessContractTestMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("fixture")) { fakeChrome(args); return; }
        if (args.length > 0 && args[0].equals("lock-probe")) {
            try (FileChannel channel = FileChannel.open(Path.of(args[1]), StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                System.out.print(lock == null ? "BUSY" : "ACQUIRED");
            }
            return;
        }
        try { processType = Class.forName("io.synexia.chromellm.standalone.ChromeProcess"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("dedicated Chrome lifecycle is absent", missing); }
        if (args.length > 0 && args[0].equals("owner-jvm")) {
            start(Path.of(args[1]), Path.of(args[2]), false, 8);
            System.out.print("STARTED");
            System.exit(0); // Deliberately bypass ordinary finally/AutoCloseable cleanup.
        }
        Path root = Files.createTempDirectory("chromellm-launch-contract-").toRealPath();
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        try {
            Path personal = root.resolve("personal");
            Files.createDirectories(personal);
            Files.writeString(personal.resolve("Preferences"), "user data stays untouched");
            refuse(IOException.class, () -> start(java, personal, false, 2));
            check(Files.readString(personal.resolve("Preferences")).equals("user data stays untouched"));
            check(!Files.exists(personal.resolve(".chromellm-profile")));
            check(!Files.exists(personal.resolve(".chromellm-profile.lock")));
            String home = System.getProperty("user.home");
            try {
                System.setProperty("user.home", root.toString());
                refuse(IOException.class, () -> start(java, root.resolve(".config/google-chrome"), false, 2));
                check(!Files.exists(root.resolve(".config/google-chrome")));
            } finally { System.setProperty("user.home", home); }
            Path cancelled = root.resolve("cancelled");
            Thread.currentThread().interrupt();
            refuse(InterruptedException.class, () -> start(java, cancelled, false, 2));
            Thread.interrupted();
            check(!Files.exists(cancelled));
            Path failed = root.resolve("failed-java");
            refuse(IOException.class, () -> start(java, failed, false, 3));
            awaitUnlocked(failed);
            if (!System.getProperty("os.name").startsWith("Windows")) {
                posixProcessCases(root, java);
            } else {
                System.out.println("POSIX synthetic-browser fixtures not run on Windows; profile/refusal/cleanup contracts executed");
            }
        } finally { deleteTree(root); }
        System.out.println("Chrome process contract checks passed: " + checks);
    }

    private static void posixProcessCases(Path root, Path java) throws Exception {
        Path executable = fixtureExecutable(root, java, "normal");
        Path profile = root.resolve("dedicated ; profile");
        AutoCloseable launched = start(executable, profile, false, 8);
        try {
            URI endpoint = (URI) processType.getDeclaredMethod("endpoint").invoke(launched);
            check(endpoint.getScheme().equals("ws") && endpoint.getHost().equals("127.0.0.1"));
            check(endpoint.getPath().equals("/devtools/page/fixture-page"));
            check(processType.getDeclaredMethod("version").invoke(launched).equals("ChromeFixture/123.0"));
            Map<?, ?> observation = (Map<?, ?>) McpJson.parse(Files.readString(profile.resolve("fixture-observation.json")));
            List<?> argv = (List<?>) observation.get("args");
            check(argv.contains("--user-data-dir=" + profile.toRealPath()));
            check(argv.contains("--remote-debugging-address=127.0.0.1"));
            check(argv.contains("--remote-debugging-port=0"));
            check(!argv.contains("--no-sandbox") && !argv.contains("--disable-web-security"));
            URI page = URI.create((String) observation.get("page"));
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                HttpResponse<String> response = get(client, page, null);
                check(response.statusCode() == 200);
                check(response.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
                check(response.headers().firstValue("Cache-Control").orElse("").equals("no-store"));
                check(get(client, page, "https://unrelated.invalid").statusCode() == 403);
                check(get(client, page.resolve("missing"), null).statusCode() == 404);
                HttpRequest post = HttpRequest.newBuilder(page).timeout(Duration.ofSeconds(2))
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                check(client.send(post, HttpResponse.BodyHandlers.discarding()).statusCode() == 405);
                check(get(client, page.resolve("bridge.js"), null).statusCode() == 200);
            }
            try (Socket raw = new Socket("127.0.0.1", page.getPort())) {
                raw.setSoTimeout(2000);
                raw.getOutputStream().write(("GET " + page.getRawPath() + " HTTP/1.1\r\nHost: foreign.invalid\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                check(new String(raw.getInputStream().readNBytes(64), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 403"));
            }
            Process otherJvm = new ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-cp", testClasspath(),
                    ChromeProcessContractTestMain.class.getName(), "lock-probe",
                    profile.resolve(".chromellm-profile.lock").toString()).start();
            try {
                check(otherJvm.waitFor(3, TimeUnit.SECONDS));
                check(new String(otherJvm.getInputStream().readNBytes(32), StandardCharsets.US_ASCII).equals("BUSY"));
                check(otherJvm.exitValue() == 0);
            } finally { if (otherJvm.isAlive()) otherJvm.destroyForcibly(); }
            refuse(IOException.class, () -> start(executable, profile, false, 2));
            check(Files.exists(profile.resolve("DevToolsActivePort")));
        } finally {
            long before = System.nanoTime();
            launched.close();
            check(System.nanoTime() - before < Duration.ofSeconds(1).toNanos());
            launched.close();
        }
        awaitUnlocked(profile);
        Files.writeString(profile.resolve("DevToolsActivePort"), "1\n/devtools/browser/stale\n");
        try (AutoCloseable reused = start(executable, profile, true, 8)) {
            check(processType.getDeclaredMethod("endpoint").invoke(reused) instanceof URI);
            Map<?, ?> observation = (Map<?, ?>) McpJson.parse(Files.readString(profile.resolve("fixture-observation.json")));
            check(((List<?>) observation.get("args")).contains("--headless=new"));
        }
        awaitUnlocked(profile);
        Path bad = root.resolve("bad-port-profile");
        refuse(IOException.class, () -> start(fixtureExecutable(root, java, "bad-port"), bad, false, 5));
        awaitUnlocked(bad);
        Path foreign = root.resolve("foreign-target-profile");
        refuse(TimeoutException.class, () -> start(fixtureExecutable(root, java, "foreign"), foreign, false, 2));
        awaitUnlocked(foreign);
        Path stalled = root.resolve("stalled-profile");
        long before = System.nanoTime();
        refuse(TimeoutException.class, () -> start(fixtureExecutable(root, java, "stall"), stalled, false, 1));
        check(System.nanoTime() - before < Duration.ofSeconds(3).toNanos());
        awaitUnlocked(stalled);
        Path interrupted = root.resolve("interrupted-startup");
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Path stallExecutable = fixtureExecutable(root, java, "stall");
        Thread waiting = Thread.ofPlatform().start(() -> {
            try (AutoCloseable unexpected = start(stallExecutable, interrupted, false, 8)) {
                outcome.set(new AssertionError("stalled browser unexpectedly became ready: " + unexpected));
            } catch (Throwable failure) { outcome.set(failure); }
        });
        long started = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Files.exists(interrupted.resolve("fixture-observation.json")) && waiting.isAlive()
                && System.nanoTime() < started) Thread.sleep(10);
        waiting.interrupt();
        waiting.join(3000);
        check(!waiting.isAlive() && outcome.get() instanceof InterruptedException);
        awaitUnlocked(interrupted);
        Path alias = root.resolve("profile-alias");
        Files.createSymbolicLink(alias, profile);
        refuse(IOException.class, () -> start(executable, alias, false, 2));
        shutdownReapsOwnedProcess(root, java, executable);
    }

    private static void shutdownReapsOwnedProcess(Path root, Path java, Path executable) throws Exception {
        Path profile = root.resolve("shutdown-profile");
        Process owner = new ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-cp", testClasspath(),
                ChromeProcessContractTestMain.class.getName(), "owner-jvm", executable.toString(), profile.toString()).start();
        ProcessHandle browser = null;
        try {
            check(owner.waitFor(12, TimeUnit.SECONDS));
            check(owner.exitValue() == 0);
            check(new String(owner.getInputStream().readNBytes(64), StandardCharsets.US_ASCII).equals("STARTED"));
            Map<?, ?> observation = (Map<?, ?>) McpJson.parse(Files.readString(profile.resolve("fixture-observation.json")));
            long pid = ((Number) observation.get("pid")).longValue();
            browser = ProcessHandle.of(pid).orElse(null);
            check(browser == null || !browser.isAlive());
            awaitUnlocked(profile);
        } finally {
            if (owner.isAlive()) owner.destroyForcibly();
            if (browser != null && browser.isAlive()) {
                browser.destroyForcibly();
                browser.onExit().get(3, TimeUnit.SECONDS);
            }
        }
    }

    private static AutoCloseable start(Path executable, Path profile, boolean headless, int seconds) throws Exception {
        try {
            return (AutoCloseable) processType.getDeclaredMethod("start", Path.class, Path.class, boolean.class, long.class)
                    .invoke(null, executable, profile, headless, System.nanoTime() + Duration.ofSeconds(seconds).toNanos());
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    private static void awaitUnlocked(Path profile) throws Exception {
        Path lock = profile.resolve(".chromellm-profile.lock");
        if (!Files.exists(lock)) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (true) {
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                try (FileLock acquired = channel.tryLock()) {
                    if (acquired != null) { checks++; return; }
                } catch (OverlappingFileLockException busy) { /* Owned cleanup has not finished yet. */ }
            }
            if (System.nanoTime() >= deadline) throw new AssertionError("profile lock retained after owned process cleanup");
            Thread.sleep(25);
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2));
        if (origin != null) request.header("Origin", origin);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path fixtureExecutable(Path root, Path java, String mode) throws Exception {
        Path script = root.resolve("browser-" + mode + ".sh");
        String content = "#!/bin/sh\nexec " + shellQuote(java.toString()) + " -XX:-UsePerfData -cp "
                + shellQuote(testClasspath()) + " "
                + ChromeProcessContractTestMain.class.getName() + " fixture " + mode + " \"$@\"\n";
        Files.writeString(script, content);
        if (!script.toFile().setExecutable(true, true)) throw new IOException("cannot create executable fixture");
        return script;
    }

    private static String testClasspath() throws Exception {
        String tests = Path.of(ChromeProcessContractTestMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().toString();
        String main = Path.of(McpJson.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().toString();
        return tests + System.getProperty("path.separator") + main;
    }

    private static String shellQuote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }

    private static void fakeChrome(String[] args) throws Exception {
        String mode = args[1];
        List<String> supplied = new ArrayList<>(List.of(args).subList(2, args.length));
        Path profile = Path.of(supplied.stream().filter(x -> x.startsWith("--user-data-dir="))
                .findFirst().orElseThrow().substring("--user-data-dir=".length()));
        String page = supplied.stream().filter(x -> x.startsWith("http://127.0.0.1:")).findFirst().orElseThrow();
        Files.writeString(profile.resolve("fixture-observation.json"),
                McpJson.encode(Map.of("args", supplied, "page", page, "pid", ProcessHandle.current().pid()), 16 * 1024));
        if (mode.equals("stall")) { new CountDownLatch(1).await(); return; }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        int port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String body;
            if (exchange.getRequestURI().getPath().equals("/json/version")) {
                body = McpJson.encode(Map.of("Browser", "ChromeFixture/123.0", "webSocketDebuggerUrl",
                        "ws://127.0.0.1:" + port + "/devtools/browser/fixture"), 4096);
            } else {
                body = McpJson.encode(List.of(Map.of("id", "fixture-page", "type", "page",
                        "url", mode.equals("foreign") ? "https://unowned.invalid/" : page,
                        "webSocketDebuggerUrl", "ws://127.0.0.1:" + port + "/devtools/page/fixture-page")), 4096);
            }
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            try (exchange) { exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); }
        });
        server.start();
        Files.writeString(profile.resolve("DevToolsActivePort"), mode.equals("bad-port")
                ? "0\n/devtools/browser/fixture\n" : port + "\n/devtools/browser/fixture\n");
        new CountDownLatch(1).await();
    }

    private static void deleteTree(Path path) throws IOException {
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private static void check(boolean condition) { if (!condition) throw new AssertionError("Chrome process check " + checks); checks++; }
    private static void refuse(Class<? extends Exception> expected, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + expected.getSimpleName()); }
        catch (Exception failure) { if (!expected.isInstance(failure)) throw failure; checks++; }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
