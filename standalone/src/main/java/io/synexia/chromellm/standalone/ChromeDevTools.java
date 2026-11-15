// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded CDP transport for one owned loopback page.
 * Adapted from Synexia's CdpClient: deadlines cover sends and waits, while another
 * command can cancel an outstanding promise. See standalone/NOTICE.md.
 */
final class ChromeDevTools implements AutoCloseable {
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING = 16;
    private final HttpClient http;
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Semaphore admission = new Semaphore(MAX_PENDING);
    private final Semaphore sending = new Semaphore(1, true);
    private final Object state = new Object();
    private volatile WebSocket socket;
    private volatile IOException failure;

    private ChromeDevTools(HttpClient http) {
        this.http = http;
    }

    static ChromeDevTools connect(URI endpoint, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        if (endpoint == null || !"ws".equals(endpoint.getScheme())
                || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() < 1
                || endpoint.getPort() > 65535 || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !endpoint.getRawPath().matches("/devtools/page/[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("an owned numeric-loopback page WebSocket is required");
        }
        HttpClient client = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .connectTimeout(Duration.ofNanos(remaining(deadline))).build();
        ChromeDevTools connection = new ChromeDevTools(client);
        CompletableFuture<WebSocket> opening = null;
        try {
            opening = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofNanos(remaining(deadline)))
                    .buildAsync(endpoint, connection.new Listener());
            WebSocket opened = await(opening, deadline);
            synchronized (connection.state) {
                if (connection.failure != null) {
                    opened.abort();
                    throw connection.failure;
                }
                connection.socket = opened;
            }
            return connection;
        } catch (IOException | InterruptedException | TimeoutException | RuntimeException error) {
            if (opening != null) {
                opening.whenComplete((opened, ignored) -> { if (opened != null) opened.abort(); });
                opening.cancel(true);
            }
            connection.close();
            throw error;
        }
    }

    Map<String, Object> command(String method, Map<String, Object> parameters, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        remaining(deadline);
        if (method == null || !method.matches("[A-Za-z]+\\.[A-Za-z][A-Za-z0-9]*")) {
            throw new IllegalArgumentException("invalid CDP method");
        }
        if (!admission.tryAcquire()) throw new IOException("Chrome protocol request capacity exceeded");
        long id = ids.incrementAndGet();
        CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
        boolean sendingAcquired = false;
        try {
            if (id <= 0) throw new IOException("Chrome protocol request identity exhausted");
            String payload = McpJson.encode(Map.of("id", id, "method", method,
                    "params", parameters), MAX_MESSAGE_BYTES);
            synchronized (state) {
                if (failure != null) throw failure;
                pending.put(id, response);
            }
            if (!sending.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("Chrome protocol send deadline exceeded");
            }
            sendingAcquired = true;
            if (failure != null) throw failure;
            try {
                await(socket.sendText(payload, true), deadline);
            } catch (IOException | InterruptedException | TimeoutException uncertainSend) {
                // Releasing the send permit does not cancel an asynchronous send.
                // Abort this connection before another sender can own that permit.
                close();
                throw uncertainSend;
            } catch (RuntimeException uncertainSend) {
                close();
                throw new IOException("Chrome protocol send failed", uncertainSend);
            }
            sending.release();
            sendingAcquired = false;
            Map<String, Object> message = await(response, deadline);
            if (message.containsKey("error")) throw new IOException("Chrome protocol command failed: " + method);
            return object(message.get("result"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (sendingAcquired) sending.release();
            pending.remove(id, response);
            response.cancel(false);
            admission.release();
        }
    }

    Object evaluate(String expression, boolean userGesture, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        Map<String, Object> answer = command("Runtime.evaluate", Map.of(
                "expression", expression, "returnByValue", true, "awaitPromise", true,
                "userGesture", userGesture), deadline);
        return value(answer);
    }

    Object invoke(String objectId, String method, Object argument, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        if (!List.of("infer", "cancel").contains(method)) {
            throw new IllegalArgumentException("unsupported Chrome bridge method");
        }
        return value(command("Runtime.callFunctionOn", Map.of(
                "objectId", objectId,
                "functionDeclaration", "async function(value) { return await this." + method + "(value); }",
                "arguments", List.of(Map.of("value", argument)),
                "returnByValue", true, "awaitPromise", true), deadline));
    }

    static Object value(Map<String, Object> answer) throws IOException {
        if (answer.containsKey("exceptionDetails")) throw new IOException("Chrome bridge rejected the operation");
        Map<String, Object> result = object(answer.get("result"));
        if (!result.containsKey("value")) throw new IOException("Chrome bridge returned no value");
        return result.get("value");
    }

    static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> raw)) throw new IOException("Chrome protocol object required");
        for (Object key : raw.keySet()) {
            if (!(key instanceof String)) throw new IOException("invalid Chrome protocol key");
        }
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException("Chrome operation deadline exceeded");
        return nanos;
    }

    private static <T> T await(CompletableFuture<T> future, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        try {
            return future.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Chrome protocol operation failed", cause);
        }
    }

    private void fail(IOException error) {
        synchronized (state) {
            if (failure == null) failure = error;
            for (Map.Entry<Long, CompletableFuture<Map<String, Object>>> entry : pending.entrySet()) {
                if (pending.remove(entry.getKey(), entry.getValue())) {
                    entry.getValue().completeExceptionally(failure);
                }
            }
        }
    }

    @Override public void close() {
        fail(new IOException("Chrome protocol connection closed"));
        WebSocket current = socket;
        if (current != null) current.abort();
        http.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder frame = new StringBuilder();
        private int bytes;
        private boolean highSurrogate;

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                count(data, last);
                frame.append(data);
                if (last) {
                    Map<String, Object> message = object(McpJson.parse(frame.toString()));
                    if (message.containsKey("id")) {
                        Object raw = message.get("id");
                        if (!(raw instanceof BigDecimal number)) throw new IOException("invalid Chrome reply ID");
                        long id;
                        try { id = number.longValueExact(); }
                        catch (ArithmeticException invalid) { throw new IOException("invalid Chrome reply ID", invalid); }
                        if (id <= 0) throw new IOException("invalid Chrome reply ID");
                        CompletableFuture<Map<String, Object>> result = pending.remove(id);
                        if (result != null) result.complete(message);
                    } else if (!(message.get("method") instanceof String)) {
                        throw new IOException("invalid Chrome protocol message");
                    }
                    frame.setLength(0);
                    bytes = 0;
                    highSurrogate = false;
                }
                webSocket.request(1);
            } catch (IOException | RuntimeException invalid) {
                frame.setLength(0);
                webSocket.abort();
                fail(invalid instanceof IOException io ? io
                        : new IOException("invalid Chrome protocol message", invalid));
            }
            return CompletableFuture.completedFuture(null);
        }

        private void count(CharSequence data, boolean last) throws IOException {
            if (data.length() > MAX_MESSAGE_BYTES - frame.length()) {
                throw new IOException("Chrome protocol message exceeds bounded size");
            }
            for (int i = 0; i < data.length(); i++) {
                char current = data.charAt(i);
                int width;
                if (highSurrogate) {
                    if (!Character.isLowSurrogate(current)) throw new IOException("malformed Chrome UTF-16");
                    highSurrogate = false;
                    width = 4;
                } else if (Character.isHighSurrogate(current)) {
                    highSurrogate = true;
                    width = 0;
                } else if (Character.isLowSurrogate(current)) {
                    throw new IOException("malformed Chrome UTF-16");
                } else width = current < 0x80 ? 1 : current < 0x800 ? 2 : 3;
                if (width > MAX_MESSAGE_BYTES - bytes) {
                    throw new IOException("Chrome protocol message exceeds bounded size");
                }
                bytes += width;
            }
            if (last && highSurrogate) throw new IOException("malformed Chrome UTF-16");
        }

        @Override public CompletionStage<?> onBinary(WebSocket webSocket,
                java.nio.ByteBuffer data, boolean last) {
            webSocket.abort();
            fail(new IOException("Chrome protocol binary messages are unsupported"));
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            fail(new IOException("Chrome protocol peer disconnected"));
            return CompletableFuture.completedFuture(null);
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            fail(new IOException("Chrome protocol transport failed", error));
        }
    }
}
