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
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class McpSseServerTest {

    private McpSseServer sseServer;
    private int port;

    private static McpSseServer newServer(final McpAuthProperties authProperties) throws Exception {
        final McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setTransport("sse");
        properties.setSsePort(0); // Dynamic ephemeral port
        // A dead peer is only noticed on the next keep-alive write, so keep that window short
        // enough for a test to observe the session being released.
        properties.setSseKeepAliveInterval(java.time.Duration.ofMillis(50));

        final McpAuthService authService = new McpAuthService(authProperties, SecretResolvers.defaults());
        final McpMessageHandler messageHandler =
                new McpMessageHandler(authService, new SkillRegistry(), List.of());

        final McpSseServer server = new McpSseServer(properties, messageHandler, authService);
        server.run();
        return server;
    }

    private static McpAuthProperties authDisabled() {
        final McpAuthProperties authProperties = new McpAuthProperties();
        authProperties.setEnabled(false);
        return authProperties;
    }

    /** Session bookkeeping happens on the server's own thread, so give it a moment to catch up. */
    private static void awaitSessionCount(final McpSseServer server, final int expected) throws Exception {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (server.getActiveSessionCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(server.getActiveSessionCount()).isEqualTo(expected);
    }

    @BeforeEach
    public void setUp() throws Exception {
        sseServer = newServer(authDisabled());
        port = sseServer.getPort();
    }

    @AfterEach
    public void tearDown() {
        if (sseServer != null) {
            sseServer.stop();
        }
    }

    private HttpResponse<InputStream> openStream(final HttpClient client, final String query) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp/sse" + query))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    @Test
    public void establishesGetSseStreamEndpoint() throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpResponse<InputStream> response = openStream(client, "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");

        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).contains("event: endpoint");
            assertThat(reader.readLine()).contains("/mcp/sse?sessionId=");
        }
    }

    /**
     * The spec flow: a client that posts against its session gets 202 on the POST and reads the
     * JSON-RPC response as a {@code message} event on the stream it is already holding open.
     */
    @Test
    public void deliversJsonRpcResponseAsSseMessageEventForSessionPost() throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpResponse<InputStream> stream = openStream(client, "");

        try (var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            reader.readLine(); // event: endpoint
            final String endpointData = reader.readLine();
            final String sessionId = endpointData.substring(endpointData.indexOf("sessionId=") + "sessionId=".length());
            reader.readLine(); // frame-terminating blank line

            final HttpRequest post = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/mcp/sse?sessionId=" + sessionId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\",\"params\":{}}"))
                    .build();
            final HttpResponse<String> postResponse = client.send(post, HttpResponse.BodyHandlers.ofString());

            assertThat(postResponse.statusCode()).isEqualTo(202);
            assertThat(postResponse.body()).isEmpty();

            assertThat(reader.readLine()).isEqualTo("event: message");
            assertThat(reader.readLine()).contains("\"id\":7").contains("\"jsonrpc\":\"2.0\"");
        }
    }

    /** Without a live session there is nowhere to stream to, so the response comes back on the POST. */
    @Test
    public void returnsResponseInPostBodyWhenNoSessionIsOpen() throws Exception {
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

    /** A session is released when its client goes away, not held until shutdown. */
    @Test
    public void releasesSessionWhenStreamCloses() throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpResponse<InputStream> stream = openStream(client, "");
        try (var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            reader.readLine();
            awaitSessionCount(sseServer, 1);
        }

        awaitSessionCount(sseServer, 0);
    }

    @Test
    public void refusesStreamsBeyondTheSessionCap() throws Exception {
        final McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setTransport("sse");
        properties.setSsePort(0);
        properties.setMaxSseSessions(1);

        final McpAuthService authService = new McpAuthService(authDisabled(), SecretResolvers.defaults());
        final McpSseServer cappedServer = new McpSseServer(properties,
                new McpMessageHandler(authService, new SkillRegistry(), List.of()), authService);
        cappedServer.run();

        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + cappedServer.getPort() + "/mcp/sse"))
                    .GET()
                    .build();

            final HttpResponse<InputStream> first = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(first.body(), StandardCharsets.UTF_8))) {
                reader.readLine();
                awaitSessionCount(cappedServer, 1);

                final HttpResponse<String> second = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(second.statusCode()).isEqualTo(503);
            }
        } finally {
            cappedServer.stop();
        }
    }

    @Test
    public void rejectsUnauthenticatedGetSseStreamWhenAuthEnabled() throws Exception {
        final McpAuthProperties authProperties = new McpAuthProperties();
        authProperties.setEnabled(true);
        authProperties.setTokens(List.of(new SecretRef("secret_token_123")));

        final McpSseServer protectedServer = newServer(authProperties);
        final int protectedPort = protectedServer.getPort();

        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest unauthReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + protectedPort + "/mcp/sse"))
                    .GET()
                    .build();
            assertThat(client.send(unauthReq, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

            // A token in the query string is not a credential: it would leak into access logs.
            final HttpRequest queryTokenReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + protectedPort + "/mcp/sse?authToken=secret_token_123"))
                    .GET()
                    .build();
            assertThat(client.send(queryTokenReq, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

            final HttpRequest authReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + protectedPort + "/mcp/sse"))
                    .header("Authorization", "Bearer secret_token_123")
                    .GET()
                    .build();
            final HttpResponse<InputStream> authResp = client.send(authReq, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(authResp.statusCode()).isEqualTo(200);
            try (var reader = new BufferedReader(new InputStreamReader(authResp.body(), StandardCharsets.UTF_8))) {
                assertThat(reader.readLine()).contains("event: endpoint");
                assertThat(reader.readLine()).contains("/mcp/sse?sessionId=");
            }
        } finally {
            protectedServer.stop();
        }
    }

    /** The transport binds loopback unless told otherwise, so it is not exposed by default. */
    @Test
    public void bindsLoopbackByDefault() {
        assertThat(new McpProperties().getBindAddress()).isEqualTo("127.0.0.1");
    }

    /** A port already in use must fail startup rather than leave the app healthy without MCP. */
    @Test
    public void failsStartupWhenPortIsUnavailable() throws Exception {
        final McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setTransport("sse");
        properties.setSsePort(port); // Already bound by the server from setUp()

        final McpAuthService authService = new McpAuthService(authDisabled(), SecretResolvers.defaults());
        final McpSseServer collidingServer = new McpSseServer(properties,
                new McpMessageHandler(authService, new SkillRegistry(), List.of()), authService);

        assertThatThrownBy(collidingServer::run).isInstanceOf(java.net.BindException.class);
    }
}
