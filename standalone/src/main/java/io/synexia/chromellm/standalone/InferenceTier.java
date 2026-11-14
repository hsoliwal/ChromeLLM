// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

/** Operator-selected execution tier; it is not a model-name or capability claim. */
public enum InferenceTier {
    FAST, REASONING, FRONTIER;

    public static InferenceTier parse(String value) {
        if (value == null) throw new IllegalArgumentException("inference tier is required");
        return switch (value) {
            case "fast" -> FAST;
            case "reasoning" -> REASONING;
            case "frontier" -> FRONTIER;
            default -> throw new IllegalArgumentException("unknown tier: " + value);
        };
    }
}
