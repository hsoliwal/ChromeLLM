// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.chromellm.standalone;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real JDK WebSocket transport against a local protocol peer. */
public final class ChromeDevToolsContractTestMain {
    private ChromeDevToolsContractTestMain() { }

    public static void main(String[] args) throws Exception {
        roundTripAndFragmentedUnicode();
        outOfOrderRepliesAndCancellationCanOverlap();
        timeoutDoesNotPoisonNextRequest();
        closeWakesOutstandingRequest();
        malformedPeerFailsRequest();
        oversizedPeerFailsRequest();
        rejectsUnownedEndpoints();
        sendDeadlineRetiresConnection();
        interruptedSendRetiresConnection();
        System.out.println("Chrome DevTools contracts: 9 passed");
    }

    private static void roundTripAndFragmentedUnicode() throws Exception {
        try (Peer peer = new Peer((request, connection) -> {
            String answer = "{\"id\":" + request.get("id")
                    + ",\"result\":{\"value\":\"same same \uD834\uDD1E\"}}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            int split = answer.indexOf("same") + 1;
            connection.frame(bytes, 0, split, false, 1);
            connection.frame(bytes, split, bytes.length - split, true, 0);
        }); ChromeDevTools cdp = ChromeDevTools.connect(peer.uri(), deadline())) {
            Map<String, Object> answer = cdp.command("Test.echo", Map.of(), deadline());
            check("same same \uD834\uDD1E".equals(answer.get("value")), "fragmented text changed");
        }
    }

    private static void outOfOrderRepliesAndCancellationCanOverlap() throws Exception {
        AtomicReference<Map<String, Object>> first = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        try (Peer peer = new Peer((request, connection) -> {
            if (first.compareAndSet(null, request)) {
                received.countDown();
            } else {
                connection.reply(request, "cancelled");
                connection.reply(first.get(), "original");
            }
        }); ChromeDevTools cdp = ChromeDevTools.connect(peer.uri(), deadline())) {
            CompletableFuture<Map<String, Object>> original = async(() ->
                    cdp.command("Test.original", Map.of(), deadline()));
            check(received.await(2, TimeUnit.SECONDS), "original command never sent");
            Map<String, Object> cancel = cdp.command("Test.cancel", Map.of(), deadline());
            check("cancelled".equals(cancel.get("value")), "wrong cancellation response");
            check("original".equals(original.get(2, TimeUnit.SECONDS).get("value")),
                    "reply ID correlation failed");
        }
    }

    private static void timeoutDoesNotPoisonNextRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Peer peer = new Peer((request, connection) -> {
            if (calls.incrementAndGet() == 1) {
                Thread.startVirtualThread(() -> {
                    try {
                        Thread.sleep(120);
                        connection.reply(request, "late");
                    } catch (Exception ignored) { }
                });
            } else {
                connection.reply(request, "fresh");
            }
        }); ChromeDevTools cdp = ChromeDevTools.connect(peer.uri(), deadline())) {
            expect(TimeoutException.class, () -> cdp.command("Test.timeout", Map.of(),
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(35)));
            check("fresh".equals(cdp.command("Test.next", Map.of(), deadline()).get("value")),
                    "late ID poisoned subsequent call");
        }
    }

    private static void closeWakesOutstandingRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        try (Peer peer = new Peer((request, connection) -> received.countDown())) {
            ChromeDevTools cdp = ChromeDevTools.connect(peer.uri(), deadline());
            try {
                CompletableFuture<Map<String, Object>> waiting = async(() ->
                        cdp.command("Test.wait", Map.of(), deadline()));
                check(received.await(2, TimeUnit.SECONDS), "request never arrived");
                cdp.close();
                try {
                    waiting.get(2, TimeUnit.SECONDS);
                    throw new AssertionError("closed request succeeded");
                } catch (java.util.concurrent.ExecutionException expected) {
                    check(expected.getCause() instanceof IOException, "close cause changed");
                }
            } finally {
                cdp.close();
            }
        }
    }

    private static void malformedPeerFailsRequest() throws Exception {
        try (Peer peer = new Peer((request, connection) -> connection.text("{bad"));
             ChromeDevTools cdp = ChromeDevTools.connect(peer.uri(), deadline())) {
            expect(IOException.class, () -> cdp.command("Test.bad", Map.of(), deadline()));
        }
    }

    private static void oversizedPeerFailsRequest() throws Exception {
        try (Peer peer = new Peer((request, connection) -> {
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) 'x');
            for (int index = 0; index < 129; index++) {
                connection.frame(chunk, 0, chunk.length, false, index == 0 ? 1 : 0);
            }
        }); ChromeDevTools cdp = ChromeDevTools.connect(peer.uri(), deadline())) {
            expect(IOException.class, () -> cdp.command("Test.large", Map.of(), deadline()));
        }
    }

    private static void rejectsUnownedEndpoints() throws Exception {
        for (String uri : new String[]{
                "ws://example.com:9222/devtools/page/x",
                "ws://127.0.0.1:9222/devtools/page/x?other=true",
                "ws://u:p@127.0.0.1:9222/devtools/page/x",
                "ws://127.0.0.1:9222/devtools/browser/x"}) {
            expect(IllegalArgumentException.class, () -> ChromeDevTools.connect(URI.create(uri), deadline()));
        }
    }

    private static long deadline() {
        return System.nanoTime() + Duration.ofSeconds(5).toNanos();
    }

    private static void sendDeadlineRetiresConnection() throws Exception {
        StalledSocket socket = new StalledSocket();
        try (ChromeDevTools cdp = stalledTransport(socket)) {
            expect(TimeoutException.class, () -> cdp.command("Test.stall", Map.of(),
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(35)));
            check(socket.aborted, "timed-out asynchronous send remained active");
            expect(IOException.class, () -> cdp.command("Test.cancel", Map.of(), deadline()));
            check(socket.sends.get() == 1, "uncertain transport admitted another send");
        }
    }

    private static void interruptedSendRetiresConnection() throws Exception {
        StalledSocket socket = new StalledSocket();
        try (ChromeDevTools cdp = stalledTransport(socket)) {
            CompletableFuture<Throwable> result = new CompletableFuture<>();
            Thread caller = Thread.startVirtualThread(() -> {
                try {
                    cdp.command("Test.stall", Map.of(), deadline());
                    result.complete(new AssertionError("interrupted send returned success"));
                } catch (Throwable failure) {
                    result.complete(failure instanceof InterruptedException && Thread.currentThread().isInterrupted()
                            ? failure : new AssertionError("send lost interruption", failure));
                }
            });
            check(socket.started.await(2, TimeUnit.SECONDS), "send never started");
            caller.interrupt();
            check(result.get(2, TimeUnit.SECONDS) instanceof InterruptedException, "wrong interruption result");
            check(socket.aborted, "interrupted asynchronous send remained active");
            expect(IOException.class, () -> cdp.command("Test.next", Map.of(), deadline()));
        }
    }

    /** Only the external WebSocket is substituted to make an indefinitely pending send deterministic. */
    private static ChromeDevTools stalledTransport(WebSocket socket) throws Exception {
        var constructor = ChromeDevTools.class.getDeclaredConstructor(HttpClient.class);
        constructor.setAccessible(true);
        ChromeDevTools cdp = constructor.newInstance(HttpClient.newHttpClient());
        var field = ChromeDevTools.class.getDeclaredField("socket");
        field.setAccessible(true);
        field.set(cdp, socket);
        return cdp;
    }

    private static final class StalledSocket implements WebSocket {
        final CompletableFuture<WebSocket> sent = new CompletableFuture<>();
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger sends = new AtomicInteger();
        volatile boolean aborted;
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (sends.incrementAndGet() > 1 && !sent.isDone()) throw new IllegalStateException("Send pending");
            started.countDown();
            return sent;
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return sent; }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendClose(int code, String reason) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long count) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return aborted; }
        @Override public boolean isInputClosed() { return aborted; }
        @Override public void abort() { aborted = true; sent.completeExceptionally(new IOException("aborted")); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("expected " + type.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static <T> CompletableFuture<T> async(Work<T> work) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try { answer.complete(work.run()); }
            catch (Throwable failure) { answer.completeExceptionally(failure); }
        });
        return answer;
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    @FunctionalInterface private interface Responder {
        void reply(Map<String, Object> request, Peer peer) throws Exception;
    }

    /** Test-only RFC 6455 peer; no production API is added to expose private state. */
    private static final class Peer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private volatile Socket client;
        private volatile OutputStream output;

        Peer(Responder responder) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = Thread.startVirtualThread(() -> {
                try (Socket socket = listener.accept()) {
                    client = socket;
                    socket.setSoTimeout(7000);
                    InputStream input = socket.getInputStream();
                    output = socket.getOutputStream();
                    StringBuilder headers = new StringBuilder();
                    while (!headers.toString().endsWith("\r\n\r\n")) {
                        int next = input.read();
                        if (next < 0 || headers.length() > 8192) throw new EOFException();
                        headers.append((char) next);
                    }
                    String key = headers.toString().lines()
                            .filter(line -> line.toLowerCase(java.util.Locale.ROOT)
                                    .startsWith("sec-websocket-key:"))
                            .findFirst().orElseThrow().substring("sec-websocket-key:".length()).trim();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                    .getBytes(StandardCharsets.US_ASCII)));
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept
                            + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    for (;;) {
                        int opcode = input.read();
                        if (opcode < 0 || (opcode & 15) == 8) break;
                        int size = input.read();
                        if (size < 0) break;
                        boolean masked = (size & 128) != 0;
                        long length = size & 127;
                        if (length == 126) length = (input.read() << 8) | input.read();
                        else if (length == 127) {
                            length = 0;
                            for (int i = 0; i < 8; i++) length = (length << 8) | input.read();
                        }
                        if (!masked || length < 0 || length > 1024 * 1024) throw new IOException("bad fixture frame");
                        byte[] mask = input.readNBytes(4);
                        byte[] body = input.readNBytes((int) length);
                        if (mask.length != 4 || body.length != length) throw new EOFException();
                        for (int i = 0; i < body.length; i++) body[i] ^= mask[i & 3];
                        Object value = McpJson.parse(new String(body, StandardCharsets.UTF_8));
                        if (!(value instanceof Map<?, ?> raw)) throw new IOException("fixture request object required");
                        @SuppressWarnings("unchecked") Map<String, Object> request = (Map<String, Object>) raw;
                        responder.reply(request, this);
                    }
                } catch (Exception ignored) {
                    // Client-close, oversized-message abortion and abrupt EOF are exercised above.
                }
            });
        }

        URI uri() { return URI.create("ws://127.0.0.1:" + listener.getLocalPort() + "/devtools/page/test"); }

        void reply(Map<String, Object> request, String value) throws IOException {
            text("{\"id\":" + request.get("id") + ",\"result\":{\"value\":\"" + value + "\"}}");
        }

        void text(String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            frame(bytes, 0, bytes.length, true, 1);
        }

        synchronized void frame(byte[] bytes, int offset, int length, boolean last, int opcode)
                throws IOException {
            output.write((last ? 128 : 0) | opcode);
            if (length < 126) output.write(length);
            else if (length <= 65535) {
                output.write(126); output.write(length >>> 8); output.write(length);
            } else {
                output.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) output.write((int) ((long) length >>> shift));
            }
            output.write(bytes, offset, length);
            output.flush();
        }

        @Override public void close() throws IOException {
            listener.close();
            Socket socket = client;
            if (socket != null) socket.close();
            worker.interrupt();
        }
    }
}
