/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.protocol.JsonRpcMessage;
import org.riptide.mcp.service.McpMessageHandler;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Runs the non-blocking Stdio IPC loop reading JSON-RPC messages from stdin and writing responses to stdout.
 */
@Slf4j
@Component
public class McpStdioRunner implements CommandLineRunner {

    private final McpProperties properties;
    private final McpMessageHandler messageHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpStdioRunner(final McpProperties properties,
                          final McpMessageHandler messageHandler) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler must not be null");
    }

    @Override
    public void run(final String... args) throws Exception {
        if (!properties.isEnabled()) {
            return;
        }

        if (!"stdio".equalsIgnoreCase(properties.getTransport())) {
            log.info("MCP transport is set to [{}]. Skipping stdio IPC runner.", properties.getTransport());
            return;
        }

        log.info("Starting Riptide MCP Server Stdio IPC Transport Loop (redirecting console logging to stderr)...");

        final var originalOut = System.out;
        System.setOut(System.err);

        final var runnerThread = new Thread(() -> {
            // Do NOT close System.in / originalOut in try-with-resources
            final var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            final var writer = new PrintWriter(originalOut, true, StandardCharsets.UTF_8);

            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonRpcMessage request = null;
                    try {
                        request = objectMapper.readValue(line, JsonRpcMessage.class);
                    } catch (final Exception parseEx) {
                        log.error("Failed to parse MCP JSON-RPC frame: {}", parseEx.getMessage());
                        final JsonRpcMessage parseErrorResponse = JsonRpcMessage.createError(null, -32700, "Parse error: " + parseEx.getMessage());
                        writer.println(objectMapper.writeValueAsString(parseErrorResponse));
                        continue;
                    }

                    try {
                        final JsonRpcMessage response = messageHandler.handleRpcMessage(request);
                        if (response != null) {
                            writer.println(objectMapper.writeValueAsString(response));
                        }
                    } catch (final Exception handlerEx) {
                        final Object reqId = request.getId();
                        log.error("Error executing MCP handler for request [{}]: {}", reqId, handlerEx.getMessage(), handlerEx);
                        final JsonRpcMessage errorResp = JsonRpcMessage.createError(reqId, -32603, "Internal error: " + handlerEx.getMessage());
                        writer.println(objectMapper.writeValueAsString(errorResp));
                    }
                }
            } catch (final Exception e) {
                log.error("Riptide MCP Stdio transport loop encountered an unhandled exception", e);
            }
        }, "mcp-stdio-runner");

        runnerThread.setDaemon(true);
        runnerThread.start();
    }

    public JsonRpcMessage handleRpcMessage(final JsonRpcMessage request) {
        return messageHandler.handleRpcMessage(request);
    }
}
