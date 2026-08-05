/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console target has to be decided here, before the logging system starts: the stdio transport
 * writes JSON-RPC frames to the process's real stdout, and a log line on that stream corrupts them.
 */
class McpStdioLoggingEnvironmentPostProcessorTest {

    private static final String CONSOLE_TARGET = "riptide.logging.console-target";

    private final McpStdioLoggingEnvironmentPostProcessor postProcessor =
            new McpStdioLoggingEnvironmentPostProcessor();

    private MockEnvironment environmentWith(final Map<String, String> properties) {
        final MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        postProcessor.postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void redirectsConsoleLoggingToStderrForTheStdioTransport() {
        final MockEnvironment environment =
                environmentWith(Map.of("riptide.mcp.enabled", "true", "riptide.mcp.transport", "stdio"));

        assertThat(environment.getProperty(CONSOLE_TARGET)).isEqualTo("System.err");
    }

    /** stdio is the default transport, so enabling MCP without naming one still redirects. */
    @Test
    void redirectsWhenTheTransportIsLeftAtItsDefault() {
        final MockEnvironment environment = environmentWith(Map.of("riptide.mcp.enabled", "true"));

        assertThat(environment.getProperty(CONSOLE_TARGET)).isEqualTo("System.err");
    }

    @Test
    void leavesConsoleLoggingOnStdoutWhenMcpIsDisabled() {
        final MockEnvironment environment = environmentWith(Map.of("riptide.mcp.enabled", "false"));

        assertThat(environment.getProperty(CONSOLE_TARGET)).isNull();
    }

    /** Only stdio shares the console stream; the SSE transport has no reason to move logs. */
    @Test
    void leavesConsoleLoggingOnStdoutForTheSseTransport() {
        final MockEnvironment environment =
                environmentWith(Map.of("riptide.mcp.enabled", "true", "riptide.mcp.transport", "sse"));

        assertThat(environment.getProperty(CONSOLE_TARGET)).isNull();
    }

    @Test
    void keepsAnExplicitOperatorSetting() {
        final MockEnvironment environment = environmentWith(Map.of(
                "riptide.mcp.enabled", "true",
                "riptide.mcp.transport", "stdio",
                CONSOLE_TARGET, "System.out"));

        assertThat(environment.getProperty(CONSOLE_TARGET)).isEqualTo("System.out");
    }
}
