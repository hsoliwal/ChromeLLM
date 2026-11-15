// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Versioned, bounded binary transport; one loaded backend serves sequential requests. */
final class StdioProtocol {
    static final int MAGIC = 0x434c4d31; // CLM1
    static final int MAX_PROMPT_BYTES = 16 * 1024 * 1024;
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private StdioProtocol() { }

    /** Caller owns streams and backend. EOF between frames closes the session normally. */
    static void serve(ModelBackend backend, InputStream input, OutputStream output) throws IOException {
        DataInputStream in = new DataInputStream(input);
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.flush();
        while (true) {
            // A partial frame is an error, including a truncated four-byte length.
            int first = in.read();
            if (first < 0) return;
            int length = (first << 24) | (in.readUnsignedByte() << 16)
                    | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            if (length < 1 || length > MAX_PROMPT_BYTES) {
                throw new IOException("invalid framed prompt length");
            }
            int maxTokens = in.readInt();
            long timeoutMillis = in.readLong();
            byte[] bytes = in.readNBytes(length);
            if (bytes.length != length) throw new EOFException("truncated framed prompt");
            byte[] text;
            byte[] id;
            byte[] hash;
            try {
                String prompt = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                ModelRequest request = new ModelRequest(prompt, maxTokens, Duration.ofMillis(timeoutMillis));
                ModelResponse response = backend.infer(request);
                text = encode(response.text(), MAX_RESPONSE_BYTES);
                id = encode(response.backendId(), 1024);
                hash = encode(response.promptSha256(), 64);
            } catch (TimeoutException error) {
                failure(out, 2, "model deadline exceeded");
                continue;
            } catch (InterruptedException error) {
                failure(out, 4, "model request interrupted");
                Thread.currentThread().interrupt();
                return;
            } catch (IllegalArgumentException error) {
                failure(out, 3, "invalid model request");
                continue;
            } catch (IOException error) {
                failure(out, 1, "model inference or encoding failed");
                continue;
            }
            // A partial transport write terminates the stream; never append another frame to it.
            out.writeInt(0);
            write(out, text);
            write(out, id);
            write(out, hash);
            out.flush();
        }
    }

    /** Shared by framing and local providers: validate and bound before allocating UTF-8 bytes. */
    static byte[] encode(String text, int limit) throws IOException {
        if (limit < 0 || text.length() > limit) throw new IOException("UTF-8 text exceeds bounded limit");
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            int width;
            if (current < 0x80) width = 1;
            else if (current < 0x800) width = 2;
            else if (Character.isHighSurrogate(current)) {
                if (i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new MalformedInputException(1);
                }
                i++;
                width = 4;
            } else if (Character.isLowSurrogate(current)) {
                throw new MalformedInputException(1);
            } else width = 3;
            if (width > limit - length) throw new IOException("UTF-8 text exceeds bounded limit");
            length += width;
        }
        byte[] bytes = new byte[length];
        ByteBuffer encoded = ByteBuffer.wrap(bytes);
        var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CoderResult result = encoder.encode(CharBuffer.wrap(text), encoded, true);
        if (result.isError()) result.throwException();
        if (!result.isUnderflow()) throw new IOException("UTF-8 encoding exceeded verified size");
        result = encoder.flush(encoded);
        if (result.isError()) result.throwException();
        if (!result.isUnderflow() || encoded.position() != length) {
            throw new IOException("UTF-8 encoding did not match verified size");
        }
        return bytes;
    }

    private static void failure(DataOutputStream out, int status, String message) throws IOException {
        out.writeInt(status);
        write(out, message.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void write(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }
}
