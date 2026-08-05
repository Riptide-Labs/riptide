/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.mcp.auth.McpAuthProperties;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.service.McpMessageHandler;
import org.riptide.mcp.skills.SkillRegistry;
import org.riptide.secrets.SecretResolvers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class McpSseServerTest {

    private McpSseServer sseServer;
    private int port;

    @BeforeEach
    public void setUp() throws Exception {
        final McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setTransport("sse");
        properties.setSsePort(0); // Dynamic ephemeral port

        final McpAuthProperties authProperties = new McpAuthProperties();
        authProperties.setEnabled(false);
        final McpAuthService authService = new McpAuthService(authProperties, SecretResolvers.defaults());
        final SkillRegistry skillRegistry = new SkillRegistry();
        final McpMessageHandler messageHandler = new McpMessageHandler(authService, skillRegistry, List.of());

        sseServer = new McpSseServer(properties, messageHandler);
        sseServer.run();
        port = sseServer.getPort();
    }

    @AfterEach
    public void tearDown() {
        if (sseServer != null) {
            sseServer.stop();
        }
    }

    @Test
    public void establishesGetSseStreamEndpoint() throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp/sse"))
                .GET()
                .build();

        final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        assertThat(response.body()).contains("event: endpoint");
        assertThat(response.body()).contains("/mcp/sse?sessionId=");
    }

    @Test
    public void handlesPostJsonRpcMessage() throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final String jsonBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}";

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp/sse"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":1");
        assertThat(response.body()).contains("\"jsonrpc\":\"2.0\"");
    }
}
