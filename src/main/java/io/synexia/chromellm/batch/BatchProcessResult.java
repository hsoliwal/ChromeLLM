package io.synexia.chromellm.batch;

import java.time.Duration;
import java.util.List;

public record BatchProcessResult(
        int discovered,
        int succeeded,
        List<BatchFailure> failures,
        Duration elapsed) {
    public BatchProcessResult {
        failures = List.copyOf(failures);
    }

    public int failed() {
        return failures.size();
    }
}
