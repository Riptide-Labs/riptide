/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.config.ConditionalOnMcpEnabled;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.protocol.JsonRpcMessage;
import org.riptide.mcp.service.McpMessageHandler;
import org.riptide.utils.HttpServerConfig;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight, zero-dependency HTTP SSE transport server for Model Context Protocol (MCP).
 * Exposes /mcp/sse for SSE stream connections (GET) and JSON-RPC messages (POST).
 *
 * <p>The MCP HTTP+SSE transport is asymmetric: a client opens the stream with GET, is told where to
 * post via the {@code endpoint} event, and then reads every JSON-RPC response as a {@code message}
 * event on that stream — the POST itself only gets an acknowledgement. Because
 * {@code com.sun.net.httpserver} closes an exchange as soon as its handler returns, the GET handler
 * has to stay parked on the session for as long as the stream lives; it runs on a virtual thread so
 * a parked session costs no platform thread.
 *
 * <p>A client that posts without a session (no {@code sessionId}, or one that has since gone away)
 * gets its response in the POST body instead. That is outside the spec's flow but is what a plain
 * request/response caller expects, and it keeps a bare {@code curl} usable against the endpoint.
 */
@Slf4j
@ConditionalOnMcpEnabled
@Component
public class McpSseServer implements CommandLineRunner {

    /** Total shutdown budget, shared across both phases — see ManagementServer for why. */
    private static final long SHUTDOWN_BUDGET_MILLIS = 2_000L;

    /**
     * Query-string splitting. Guava rather than {@code String.split}, whose single-argument form
     * silently drops trailing empty fields — a {@code ?a=1&b=} would lose its last parameter.
     * {@link #QUERY_PAIR} keeps the value intact when it contains its own {@code =}.
     */
    private static final Splitter QUERY_PARAMS = Splitter.on('&').omitEmptyStrings();
    private static final Splitter QUERY_PAIR = Splitter.on('=').limit(2);

    private final McpProperties properties;
    private final McpMessageHandler messageHandler;
    private final McpAuthService authService;
    private final ObjectMapper objectMapper;
    private final Map<String, SseSession> activeSessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;
    /**
     * Set before the sessions are closed, so a GET that registers in the window between
     * that sweep and the listening socket closing does not park a fresh pump for the whole
     * grace period — the exact stall this change exists to remove.
     */
    private volatile boolean stopping;

    public McpSseServer(final McpProperties properties,
                        final McpMessageHandler messageHandler,
                        final McpAuthService authService,
                        final ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void run(final String... args) throws Exception {
        if (!properties.isEnabled()) {
            return;
        }

        if (!"sse".equalsIgnoreCase(properties.getTransport())) {
            return;
        }

        final String bindAddress = properties.getBindAddress();
        final int port = properties.getSsePort();
        log.info("Starting Riptide MCP Server HTTP/SSE Transport on {}:{}...", bindAddress, port);

        // before create(), not after: the JDK reads its server config in a static
        // initializer that runs on the first HttpServer in the process (#545)
        HttpServerConfig.ensureApplied();
        // A failure here is fatal on purpose: the operator asked for the SSE transport, and a
        // logged-and-swallowed bind error leaves the process reporting healthy while every MCP
        // client gets connection refused.
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/mcp/sse", new SseHandler());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        log.info("Riptide MCP Server HTTP/SSE Transport listening at http://{}:{}/mcp/sse", bindAddress, getPort());

        if (!authService.isAuthRequired()) {
            log.warn("MCP SSE transport is running without authentication "
                    + "(riptide.mcp.auth.enabled=false). Anyone who can reach {}:{} can query flow telemetry.",
                    bindAddress, getPort());
        }
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : properties.getSsePort();
    }

    /**
     * Sessions first, then a bounded stop (#545). The order is the point: every live SSE
     * stream is a GET handler parked for as long as the stream lives, so stopping the server
     * first would spend the whole grace period waiting for exchanges that end only when
     * their session is closed. {@link SseSession#close()} both clears the flag and wakes the
     * parked pump — closing without the wake-up left the handler asleep in its keep-alive
     * poll, so the budget expired and {@code shutdownNow()} killed it by interrupt anyway,
     * which is precisely what bounding the shutdown exists to avoid.
     */
    @PreDestroy
    public void stop() {
        if (server == null) {
            return;
        }
        stopping = true;
        activeSessions.values().forEach(SseSession::close);
        activeSessions.clear();
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_BUDGET_MILLIS);
        server.stop(graceSeconds(deadline));
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    log.warn("MCP SSE transport still had exchanges in flight after {}ms; interrupting them",
                            SHUTDOWN_BUDGET_MILLIS);
                    executor.shutdownNow();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        log.info("Stopped Riptide MCP Server HTTP/SSE Transport.");
    }

    /**
     * Whole seconds left, rounded up and never below one. Truncating gave away most of a
     * second and, worse, would silently become {@code stop(0)} — the immediate close this
     * change exists to replace — if the budget ever shrank below two seconds.
     */
    private static int graceSeconds(final long deadlineNanos) {
        final long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
        return (int) Math.max(1L, (remaining + 999_999_999L) / 1_000_000_000L);
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * One SSE stream: the frames waiting to go out and the exchange they go out on. The GET handler
     * owns the writing; everything else only ever hands it a frame.
     */
    private static final class SseSession {
        /** Queued by close() purely to end the pump's poll; never written to the wire. */
        private static final String WAKE = "";

        private final BlockingQueue<String> pending = new LinkedBlockingQueue<>();
        private volatile boolean open = true;

        boolean offer(final String frame) {
            return open && pending.offer(frame);
        }

        /**
         * Closes the stream AND wakes the pump. Setting the flag alone was not enough: the
         * pump re-reads it only after its keep-alive poll returns, so at the default 15s
         * interval a shutdown waited out the poll and then killed the handler by interrupt —
         * the very thing bounding the shutdown was meant to avoid. The sentinel goes onto the
         * queue directly, bypassing offer()'s own open-check, which by then is false.
         */
        void close() {
            open = false;
            // add, not offer: the queue is unbounded, so this cannot fail, and add() says so
            pending.add(WAKE);
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

            if (activeSessions.size() >= properties.getMaxSseSessions()) {
                log.warn("Refusing MCP SSE stream: {} sessions already open (riptide.mcp.max-sse-sessions).",
                        activeSessions.size());
                sendJsonResponse(exchange, 503, JsonRpcMessage.createError(null, -32000, "Too many active MCP SSE sessions"));
                return;
            }

            final String sessionId = UUID.randomUUID().toString();
            final SseSession session = new SseSession();
            activeSessions.put(sessionId, session);
            if (stopping) {
                // registered after stop()'s sweep had already run: close it here or its pump
                // parks for the keep-alive interval and burns the whole grace period
                session.close();
                activeSessions.remove(sessionId);
                sendJsonResponse(exchange, 503, JsonRpcMessage.createError(null, -32000, "Server is shutting down"));
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");

            try (exchange) {
                exchange.sendResponseHeaders(200, 0); // Chunked/Streaming response
                final OutputStream os = exchange.getResponseBody();
                write(os, "event: endpoint\ndata: /mcp/sse?sessionId=" + sessionId + "\n\n");
                log.debug("Established MCP SSE stream session [{}]", sessionId);
                pump(session, os);
            } catch (final IOException e) {
                log.debug("MCP SSE stream session [{}] ended: {}", sessionId, e.getMessage());
            } finally {
                // The only place a session is removed: whatever ends the stream — client
                // disconnect, failed keep-alive, shutdown — lands here.
                activeSessions.remove(sessionId);
                session.close();
                log.debug("Closed MCP SSE stream session [{}]", sessionId);
            }
        }

        /**
         * Parks on the session writing frames as they arrive. A keep-alive comment on each idle
         * window turns a client that vanished without a FIN into a write failure, which is what
         * ends the stream and drops the session.
         */
        private void pump(final SseSession session, final OutputStream os) throws IOException {
            while (session.open) {
                final String frame;
                try {
                    frame = session.pending.poll(
                            properties.getSseKeepAliveInterval().toMillis(), TimeUnit.MILLISECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (SseSession.WAKE.equals(frame)) {
                    // the wake-up from close(), not a frame. Keyed on the sentinel rather
                    // than on session.open: a real frame already dequeued must still be
                    // written, because deliver() answered its POST with 202 — a promise
                    // that the response goes out on the stream
                    return;
                }
                write(os, frame != null ? frame : ": keep-alive\n\n");
            }
        }

        private void write(final OutputStream os, final String frame) throws IOException {
            os.write(frame.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        private void handlePostMessage(final HttpExchange exchange) throws IOException {
            final String authToken = extractAuthToken(exchange);
            if (authService.isAuthRequired() && !authService.authenticate(authToken)) {
                sendJsonResponse(exchange, 401, JsonRpcMessage.createError(null, -32001, "Unauthorized: invalid MCP authentication token"));
                return;
            }

            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

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
                if (response == null) {
                    exchange.sendResponseHeaders(202, -1); // Accepted (for notifications)
                    exchange.close();
                    return;
                }
                deliver(exchange, queryParam(exchange, "sessionId"), response);
            } catch (final Exception ex) {
                log.error("Internal error handling MCP SSE message: {}", ex.getMessage(), ex);
                sendJsonResponse(exchange, 500, JsonRpcMessage.createError(request != null ? request.getId() : null, -32603, "Internal error: " + ex.getMessage()));
            }
        }

        /**
         * Spec flow when the post names a live session: the response goes out as a {@code message}
         * event on that stream and the POST is acknowledged with 202. Without one there is nowhere
         * to stream it, so it goes back in the POST body.
         */
        private void deliver(final HttpExchange exchange, final String sessionId, final JsonRpcMessage response)
                throws IOException {
            final SseSession session = sessionId != null ? activeSessions.get(sessionId) : null;
            if (session == null) {
                if (sessionId != null) {
                    log.debug("MCP SSE session [{}] is not open; returning response in the POST body.", sessionId);
                }
                sendJsonResponse(exchange, 200, response);
                return;
            }

            final String frame = "event: message\ndata: " + objectMapper.writeValueAsString(response) + "\n\n";
            if (!session.offer(frame)) {
                sendJsonResponse(exchange, 200, response);
                return;
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        }

        /**
         * The bearer token, from the {@code Authorization} header only. Query parameters are not
         * accepted: they land in proxy and access logs.
         */
        private String extractAuthToken(final HttpExchange exchange) {
            final String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7).trim();
            }
            return null;
        }

        private String queryParam(final HttpExchange exchange, final String name) {
            final String query = exchange.getRequestURI().getQuery();
            if (query == null || query.isBlank()) {
                return null;
            }
            for (final String param : QUERY_PARAMS.split(query)) {
                final List<String> kv = QUERY_PAIR.splitToList(param);
                if (kv.size() == 2 && name.equals(kv.get(0))) {
                    return kv.get(1).trim();
                }
            }
            return null;
        }

        private void sendJsonResponse(final HttpExchange exchange, final int statusCode, final JsonRpcMessage message) throws IOException {
            final byte[] jsonBytes = objectMapper.writeValueAsBytes(message);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(statusCode, jsonBytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(jsonBytes);
                os.flush();
            }
        }
    }
}
