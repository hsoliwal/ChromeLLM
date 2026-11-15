// SPDX-License-Identifier: Apache-2.0
// Copyright © 2026 Hitesh Soliwal and Contributors to the Synexia Project.
package io.synexia.chromellm.standalone;

/** Maven/CI entry point for the headless agent contract suites. */
public final class AgentContractTestMain {
    private AgentContractTestMain() { }

    public static void main(String[] args) throws Exception {
        AgentEngineContractTestMain.main(new String[0]);
        WorkspaceToolsContractTestMain.main(new String[0]);
        AgentTransportContractTestMain.main(new String[0]);
    }
}
