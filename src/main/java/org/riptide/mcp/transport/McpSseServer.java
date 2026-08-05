/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.protocol.JsonRpcMessage;
import org.riptide.mcp.service.McpMessageHandler;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Lightweight, zero-dependency HTTP SSE transport server for Model Context Protocol (MCP).
 * Exposes /mcp/sse endpoint for SSE stream connections and HTTP POST JSON-RPC messages.
 */
@Slf4j
@Component
public class McpSseServer implements CommandLineRunner {

    private final McpProperties properties;
    private final McpMessageHandler messageHandler;
    private final McpAuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, HttpExchange> activeSessions = new ConcurrentHashMap<>();
    private HttpServer server;

    public McpSseServer(final McpProperties properties,
                        final McpMessageHandler messageHandler,
                        final McpAuthService authService) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    @Override
    public void run(final String... args) throws Exception {
        if (!properties.isEnabled()) {
            return;
        }

        if (!"sse".equalsIgnoreCase(properties.getTransport())) {
            return;
        }

        final int port = properties.getSsePort();
        log.info("Starting Riptide MCP Server HTTP/SSE Transport on port {}...", port);

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/mcp/sse", new SseHandler());
            final ThreadFactory threadFactory = (Runnable r) -> {
                final Thread t = new Thread(r, "mcp-sse-worker");
                t.setDaemon(true);
                return t;
            };
            server.setExecutor(Executors.newFixedThreadPool(16, threadFactory));
            server.start();
            log.info("Riptide MCP Server HTTP/SSE Transport successfully listening at http://localhost:{}/mcp/sse", getPort());
        } catch (final Exception e) {
            log.error("Failed to start Riptide MCP Server HTTP/SSE Transport on port {}: {}", port, e.getMessage(), e);
        }
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : properties.getSsePort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("Stopped Riptide MCP Server HTTP/SSE Transport.");
        }
    }

    private class SseHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            final String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                handleGetSseStream(exchange);
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePostMessage(exchange);
            } else {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                exchange.close();
            }
        }

        private void handleGetSseStream(final HttpExchange exchange) throws IOException {
            final String authToken = extractAuthToken(exchange);
            if (authService.isAuthRequired() && !authService.authenticate(authToken)) {
                sendJsonResponse(exchange, 401, JsonRpcMessage.createError(null, -32001, "Unauthorized: invalid MCP authentication token"));
                return;
            }

            final String sessionId = UUID.randomUUID().toString();

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            final String endpointPayload = "event: endpoint\ndata: /mcp/sse?sessionId=" + sessionId + "\n\n";
            final byte[] payloadBytes = endpointPayload.getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(200, 0); // Chunked/Streaming response
            final var os = exchange.getResponseBody();
            os.write(payloadBytes);
            os.flush();

            activeSessions.put(sessionId, exchange);
            log.debug("Established MCP SSE stream session [{}]", sessionId);
        }

        private void handlePostMessage(final HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            final String authToken = extractAuthToken(exchange);
            if (authService.isAuthRequired() && !authService.authenticate(authToken)) {
                sendJsonResponse(exchange, 401, JsonRpcMessage.createError(null, -32001, "Unauthorized: invalid MCP authentication token"));
                return;
            }

            final String body;
            try (var reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                body = reader.lines().reduce("", (acc, line) -> acc + line);
            }

            if (body.isBlank()) {
                sendJsonResponse(exchange, 400, JsonRpcMessage.createError(null, -32600, "Empty payload"));
                return;
            }

            JsonRpcMessage request;
            try {
                request = objectMapper.readValue(body, JsonRpcMessage.class);
            } catch (final Exception parseEx) {
                sendJsonResponse(exchange, 400, JsonRpcMessage.createError(null, -32700, "Parse error: " + parseEx.getMessage()));
                return;
            }

            try {
                final JsonRpcMessage response = messageHandler.handleRpcMessage(request, authToken);
                if (response != null) {
                    sendJsonResponse(exchange, 200, response);
                } else {
                    exchange.sendResponseHeaders(202, -1); // Accepted (for notifications)
                    exchange.close();
                }
            } catch (final Exception ex) {
                log.error("Internal error handling MCP SSE message: {}", ex.getMessage(), ex);
                sendJsonResponse(exchange, 500, JsonRpcMessage.createError(request != null ? request.getId() : null, -32603, "Internal error: " + ex.getMessage()));
            }
        }

        private String extractAuthToken(final HttpExchange exchange) {
            final String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7).trim();
            }
            final String query = exchange.getRequestURI().getQuery();
            if (query != null && !query.isBlank()) {
                for (final String param : query.split("&")) {
                    final String[] kv = param.split("=", 2);
                    if (kv.length == 2 && ("authToken".equalsIgnoreCase(kv[0]) || "token".equalsIgnoreCase(kv[0]))) {
                        return kv[1].trim();
                    }
                }
            }
            return null;
        }

        private void sendJsonResponse(final HttpExchange exchange, final int statusCode, final JsonRpcMessage message) throws IOException {
            final byte[] jsonBytes = objectMapper.writeValueAsBytes(message);
            exchange.sendResponseHeaders(statusCode, jsonBytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(jsonBytes);
                os.flush();
            }
        }
    }
}
