// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.time.Duration;
import java.util.Objects;

/** A bounded, immutable request independent of any browser or model vendor. */
public record ModelRequest(String prompt, int maxTokens, Duration timeout) {
    public ModelRequest {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(timeout, "timeout");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (maxTokens < 1 || maxTokens > 32768) {
            throw new IllegalArgumentException("maxTokens must be in [1, 32768]");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be in (0, 1 hour]");
        }
    }
}
