/*
 * Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.chromellm.standalone;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Explicitly registered capability; model output cannot install implementations. */
interface AgentTool {
    String name();
    String description();
    Map<String, Object> inputSchema();
    void validate(Map<String, Object> arguments) throws IOException;
    Object execute(Map<String, Object> arguments, AgentDeadline deadline)
            throws IOException, InterruptedException, TimeoutException;
}
