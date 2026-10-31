// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Shared bounded, exact UTF-8 input reader for browserless inference entrypoints. */
final class PromptFileReader {
    static final int MAX_UTF8_BYTES = 4 * 1024 * 1024;

    private PromptFileReader() { }

    static String read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("prompt must be a readable regular file");
        }
        final byte[] bytes;
        try (InputStream stream = Files.newInputStream(file)) {
            // Read at most one byte beyond the cap, including if the file grows.
            bytes = stream.readNBytes(MAX_UTF8_BYTES + 1);
        }
        if (bytes.length > MAX_UTF8_BYTES) {
            throw new IOException("prompt exceeds 4 MiB UTF-8 limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("prompt is not well-formed UTF-8", malformed);
        }
    }
}
