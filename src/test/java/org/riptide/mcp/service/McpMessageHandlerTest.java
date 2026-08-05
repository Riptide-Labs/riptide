/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.mcp.auth.McpAuthProperties;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.protocol.JsonRpcMessage;
import org.riptide.mcp.skills.SkillRegistry;
import org.riptide.mcp.tools.AutoMitigationRulesTool;
import org.riptide.mcp.tools.McpTool;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class McpMessageHandlerTest {

    private McpMessageHandler messageHandler;
    private McpAuthService authService;
    private McpAuthProperties authProperties;
    private SkillRegistry skillRegistry;

    @BeforeEach
    public void setUp() {
        authProperties = new McpAuthProperties();
        authProperties.setEnabled(false);
        authService = new McpAuthService(authProperties, SecretResolvers.defaults());
        skillRegistry = new SkillRegistry();
        final List<McpTool> tools = List.of(new AutoMitigationRulesTool());

        messageHandler = new McpMessageHandler(authService, skillRegistry, tools);
    }

    @Test
    public void handlesInitializeRequest() {
        final JsonRpcMessage request = JsonRpcMessage.createRequest(1, "initialize", Map.of("protocolVersion", "2024-11-05"));
        final JsonRpcMessage response = messageHandler.handleRpcMessage(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1);
        assertThat(response.getError()).isNull();
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result.get("protocolVersion")).isEqualTo("2024-11-05");
        assertThat(result).containsKey("capabilities");
        assertThat(result).containsKey("serverInfo");
    }

    @Test
    public void handlesPingRequest() {
        final JsonRpcMessage request = JsonRpcMessage.createRequest(2, "ping", Map.of());
        final JsonRpcMessage response = messageHandler.handleRpcMessage(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(2);
        assertThat(response.getError()).isNull();
    }

    @Test
    public void handlesToolsListRequest() {
        final JsonRpcMessage request = JsonRpcMessage.createRequest(3, "tools/list", Map.of());
        final JsonRpcMessage response = messageHandler.handleRpcMessage(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsKey("tools");
    }

    @Test
    public void handlesToolCallRequest() {
        final JsonRpcMessage request = JsonRpcMessage.createRequest(4, "tools/call", Map.of(
                "name", "riptide_generate_mitigation_rules",
                "arguments", Map.of("target_ip", "192.0.2.1", "attack_type", "SYN Flood")
        ));
        final JsonRpcMessage response = messageHandler.handleRpcMessage(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(4);
        assertThat(response.getError()).isNull();
        @SuppressWarnings("unchecked")
        final Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsKey("content");
    }

    @Test
    public void handlesPromptsListAndGet() {
        final JsonRpcMessage listReq = JsonRpcMessage.createRequest(5, "prompts/list", Map.of());
        final JsonRpcMessage listResp = messageHandler.handleRpcMessage(listReq);
        assertThat(listResp.getError()).isNull();

        final JsonRpcMessage getReq = JsonRpcMessage.createRequest(6, "prompts/get", Map.of("name", "riptide-ddos-mitigation-triage"));
        final JsonRpcMessage getResp = messageHandler.handleRpcMessage(getReq);
        assertThat(getResp.getError()).isNull();
    }

    @Test
    public void handlesResourcesListAndRead() {
        final JsonRpcMessage listReq = JsonRpcMessage.createRequest(7, "resources/list", Map.of());
        final JsonRpcMessage listResp = messageHandler.handleRpcMessage(listReq);
        assertThat(listResp.getError()).isNull();

        final JsonRpcMessage readReq = JsonRpcMessage.createRequest(8, "resources/read", Map.of("uri", "resource://riptide/skills/riptide-ddos-mitigation-triage"));
        final JsonRpcMessage readResp = messageHandler.handleRpcMessage(readReq);
        assertThat(readResp.getError()).isNull();
    }

    @Test
    public void rejectsUnauthorizedRequestWhenAuthEnabled() {
        authProperties.setEnabled(true);
        authProperties.setTokens(List.of(new SecretRef("secret_token_123")));
        final McpAuthService auth = new McpAuthService(authProperties, SecretResolvers.defaults());
        final McpMessageHandler authHandler = new McpMessageHandler(auth, skillRegistry, List.of());

        final JsonRpcMessage request = JsonRpcMessage.createRequest(9, "ping", Map.of());
        final JsonRpcMessage response = authHandler.handleRpcMessage(request);

        assertThat(response.getError()).isNotNull();
        assertThat(response.getError().getCode()).isEqualTo(-32001);
    }

    @Test
    public void acceptsAuthorizedRequestWithValidToken() {
        authProperties.setEnabled(true);
        authProperties.setTokens(List.of(new SecretRef("secret_token_123")));
        final McpAuthService auth = new McpAuthService(authProperties, SecretResolvers.defaults());
        final McpMessageHandler authHandler = new McpMessageHandler(auth, skillRegistry, List.of());

        final JsonRpcMessage request = JsonRpcMessage.createRequest(10, "ping", Map.of("_meta", Map.of("authToken", "secret_token_123")));
        final JsonRpcMessage response = authHandler.handleRpcMessage(request);

        assertThat(response.getError()).isNull();
    }
}
