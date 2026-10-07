package io.synexia.chromellm.engine;
import io.synexia.chromellm.api.DiffusionRequest;
import io.synexia.chromellm.api.DiffusionResult;
public interface DiffusionEngine extends AutoCloseable {
    DiffusionResult generate(DiffusionRequest request);
    @Override default void close() {}
}
