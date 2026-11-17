// SPDX-License-Identifier: MIT
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/** A replaceable model-execution port for independently selected inference providers. */
public interface ModelBackend extends AutoCloseable {
    String id();

    ModelResponse infer(ModelRequest request) throws IOException, InterruptedException, TimeoutException;

    @Override
    default void close() { }
}
