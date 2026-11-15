package io.synexia.chromellm.webmcp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Chrome Native Messaging framing: uint32 in host byte order, strict UTF-8. */
final class NativeFraming {
    static final int MAX_BYTES = 1024 * 1024;

    private NativeFraming() { }

    static String read(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) return null;
        byte[] sizeBytes = new byte[4];
        sizeBytes[0] = (byte) first;
        byte[] rest = in.readNBytes(3);
        if (rest.length != 3) throw new EOFException("truncated length");
        System.arraycopy(rest, 0, sizeBytes, 1, 3);
        long size = Integer.toUnsignedLong(ByteBuffer.wrap(sizeBytes).order(ByteOrder.nativeOrder()).getInt());
        if (size == 0 || size > MAX_BYTES) throw new IOException("invalid frame size");
        byte[] message = in.readNBytes((int) size);
        if (message.length != size) throw new EOFException("truncated body");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(message)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("invalid UTF-8", e);
        }
    }

    static void write(OutputStream out, String json) throws IOException {
        byte[] message = json.getBytes(StandardCharsets.UTF_8);
        if (message.length == 0 || message.length > MAX_BYTES) throw new IOException("response too large");
        byte[] size = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(message.length).array();
        out.write(size);
        out.write(message);
        out.flush();
    }
}
