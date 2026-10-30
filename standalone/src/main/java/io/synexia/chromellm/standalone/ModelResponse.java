// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.util.Objects;

/** Model-generated candidate text; its SHA-256 binds the input, not factual correctness. */
public record ModelResponse(String text, String backendId, String promptSha256) {
    public ModelResponse {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(backendId, "backendId");
        Objects.requireNonNull(promptSha256, "promptSha256");
        if (text.isBlank() || backendId.isBlank() || !promptSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("response requires text, backend ID and SHA-256 prompt receipt");
        }
    }
}
