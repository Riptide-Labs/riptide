/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.mcp.auth.McpAuthProperties;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.protocol.JsonRpcMessage;
import org.riptide.mcp.service.McpMessageHandler;
import org.riptide.mcp.skills.SkillRegistry;
import org.riptide.secrets.SecretResolvers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class McpStdioRunnerTest {

    private McpStdioRunner stdioRunner;
    private McpProperties properties;

    @BeforeEach
    public void setUp() {
        properties = new McpProperties();
        properties.setEnabled(true);
        properties.setTransport("stdio");

        final McpAuthProperties authProperties = new McpAuthProperties();
        authProperties.setEnabled(false);
        final McpAuthService authService = new McpAuthService(authProperties, SecretResolvers.defaults());
        final SkillRegistry skillRegistry = new SkillRegistry();
        final McpMessageHandler messageHandler = new McpMessageHandler(authService, skillRegistry, List.of());

        stdioRunner = new McpStdioRunner(properties, messageHandler);
    }

    @Test
    public void processesRpcMessagesViaStdioRunner() {
        final JsonRpcMessage pingReq = JsonRpcMessage.createRequest(100, "ping", Map.of());
        final JsonRpcMessage pingResp = stdioRunner.handleRpcMessage(pingReq);

        assertThat(pingResp).isNotNull();
        assertThat(pingResp.getId()).isEqualTo(100);
        assertThat(pingResp.getError()).isNull();
    }
}
