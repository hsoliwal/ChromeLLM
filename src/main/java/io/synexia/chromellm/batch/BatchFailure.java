package io.synexia.chromellm.batch;

import java.nio.file.Path;
import java.util.Objects;

public record BatchFailure(Path input, String message) {
    public BatchFailure {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(message, "message");
    }
}
