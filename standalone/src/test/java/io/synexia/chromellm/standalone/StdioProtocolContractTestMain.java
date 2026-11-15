// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Transport contract checks; the fixture generates no trained-model inference. */
public final class StdioProtocolContractTestMain {
    private static int checks;
    private StdioProtocolContractTestMain() { }

    public static int run() throws Exception {
        checks = 0;
        AtomicInteger calls = new AtomicInteger();
        ModelBackend model = new ModelBackend() {
            @Override public String id() { return "fixture"; }
            @Override public ModelResponse infer(ModelRequest request) throws IOException, TimeoutException {
                calls.incrementAndGet();
                if (request.prompt().equals("timeout")) throw new TimeoutException();
                if (request.prompt().equals("failure")) throw new IOException();
                try {
                    return new ModelResponse("reply:" + request.prompt(), id(),
                            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(request.prompt().getBytes(StandardCharsets.UTF_8))));
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new AssertionError(impossible);
                }
            }
        };
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        frame(requests, "தமிழ்\nJava 🧪".getBytes(StandardCharsets.UTF_8), 8, 1000);
        frame(requests, "second".getBytes(StandardCharsets.UTF_8), 9, 1000);
        frame(requests, "timeout".getBytes(StandardCharsets.UTF_8), 8, 1000);
        frame(requests, "failure".getBytes(StandardCharsets.UTF_8), 8, 1000);
        frame(requests, "bad limits".getBytes(StandardCharsets.UTF_8), 0, 1000);
        frame(requests, new byte[]{(byte) 0xc0, (byte) 0x80}, 8, 1000);
        frame(requests, "after failure".getBytes(StandardCharsets.UTF_8), 8, 1000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StdioProtocol.serve(model, new ByteArrayInputStream(requests.toByteArray()), output);
        DataInputStream read = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        require(read.readInt() == 0x434c4d31);
        for (String prompt : new String[]{"தமிழ்\nJava 🧪", "second"}) {
            require(read.readInt() == 0);
            require(string(read).equals("reply:" + prompt));
            require(string(read).equals("fixture"));
            require(string(read).equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompt.getBytes(StandardCharsets.UTF_8)))));
        }
        for (int status : new int[]{2, 1, 3, 1}) {
            require(read.readInt() == status);
            require(!string(read).isBlank());
        }
        require(read.readInt() == 0);
        require(string(read).equals("reply:after failure"));
        string(read);
        string(read);
        require(read.read() == -1);
        require(calls.get() == 5); // Invalid UTF-8/limits never invoke the model.
        for (byte[] bad : new byte[][]{
                {0}, {0, 0, 0}, {0, 0, 0, 0}, {-1, -1, -1, -1},
                {1, 0, 0, 1}, {0, 0, 0, 2, 0, 0, 0, 8}}) {
            try {
                StdioProtocol.serve(model, new ByteArrayInputStream(bad), new ByteArrayOutputStream());
                throw new AssertionError("malformed frame accepted");
            } catch (IOException expected) { checks++; }
        }
        ByteArrayOutputStream partialRequests = new ByteArrayOutputStream();
        frame(partialRequests, "partial".getBytes(StandardCharsets.UTF_8), 8, 1000);
        frame(partialRequests, "must not run".getBytes(StandardCharsets.UTF_8), 8, 1000);
        PartialWrite outputFailure = new PartialWrite();
        int beforeFailure = calls.get();
        try {
            StdioProtocol.serve(model, new ByteArrayInputStream(partialRequests.toByteArray()), outputFailure);
            throw new AssertionError("partial response write was recovered on a corrupted stream");
        } catch (IOException expected) {
            require(expected == outputFailure.failure);
        }
        require(outputFailure.failed);
        require(outputFailure.bytes.size() == 13);
        require(outputFailure.writesAfterFailure == 0);
        require(calls.get() == beforeFailure + 1);
        return checks;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--bounded-memory")) {
            boundedMemory();
            return;
        }
        System.out.println("stdio transport checks passed: " + run());
    }

    /** A small heap fits the supplied text but not a second unbounded encoding allocation. */
    private static void boundedMemory() throws Exception {
        ModelBackend model = new ModelBackend() {
            @Override public String id() { return "fixture"; }
            @Override public ModelResponse infer(ModelRequest request) {
                return new ModelResponse("x".repeat(32 * 1024 * 1024), id(), "0".repeat(64));
            }
        };
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        frame(request, new byte[]{'x'}, 1, 1000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StdioProtocol.serve(model, new ByteArrayInputStream(request.toByteArray()), output);
        DataInputStream read = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        require(read.readInt() == StdioProtocol.MAGIC);
        require(read.readInt() == 1);
        require(string(read).equals("model inference or encoding failed"));
        require(read.read() == -1);
        System.out.println("bounded encoding memory check passed");
    }

    private static void frame(ByteArrayOutputStream target, byte[] prompt, int tokens, long timeout)
            throws IOException {
        DataOutputStream out = new DataOutputStream(target);
        out.writeInt(prompt.length);
        out.writeInt(tokens);
        out.writeLong(timeout);
        out.write(prompt);
    }

    private static String string(DataInputStream input) throws IOException {
        return new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("stdio contract failed");
    }

    /** Fails once inside a success frame, then accepts writes to expose accidental recovery. */
    private static final class PartialWrite extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final IOException failure = new IOException("synthetic partial response write");
        private boolean failed;
        private int writesAfterFailure;

        @Override public void write(int value) throws IOException {
            if (!failed && bytes.size() == 13) {
                failed = true;
                throw failure;
            }
            if (failed) writesAfterFailure++;
            bytes.write(value);
        }
    }
}
