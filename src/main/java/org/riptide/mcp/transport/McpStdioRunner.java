/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.protocol.JsonRpcMessage;
import org.riptide.mcp.skills.SkillRegistry;
import org.riptide.mcp.tools.AutoMitigationRulesTool;
import org.riptide.mcp.tools.GeoAsnTool;
import org.riptide.mcp.tools.HostTraceTool;
import org.riptide.mcp.tools.InterfaceUtilizationTool;
import org.riptide.mcp.tools.TopTalkersTool;
import org.riptide.mcp.tools.TrafficSpikesTool;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the non-blocking Stdio IPC loop reading JSON-RPC messages from stdin and writing responses to stdout.
 */
@Slf4j
@Component
public class McpStdioRunner implements CommandLineRunner {

    private final McpProperties properties;
    private final McpAuthService authService;
    private final SkillRegistry skillRegistry;
    private final TopTalkersTool topTalkersTool;
    private final InterfaceUtilizationTool interfaceUtilizationTool;
    private final HostTraceTool hostTraceTool;
    private final GeoAsnTool geoAsnTool;
    private final TrafficSpikesTool trafficSpikesTool;
    private final AutoMitigationRulesTool autoMitigationRulesTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpStdioRunner(final McpProperties properties,
                          final McpAuthService authService,
                          final SkillRegistry skillRegistry,
                          final TopTalkersTool topTalkersTool,
                          final InterfaceUtilizationTool interfaceUtilizationTool,
                          final HostTraceTool hostTraceTool,
                          final GeoAsnTool geoAsnTool,
                          final TrafficSpikesTool trafficSpikesTool,
                          final AutoMitigationRulesTool autoMitigationRulesTool) {
        this.properties = properties;
        this.authService = authService;
        this.skillRegistry = skillRegistry;
        this.topTalkersTool = topTalkersTool;
        this.interfaceUtilizationTool = interfaceUtilizationTool;
        this.hostTraceTool = hostTraceTool;
        this.geoAsnTool = geoAsnTool;
        this.trafficSpikesTool = trafficSpikesTool;
        this.autoMitigationRulesTool = autoMitigationRulesTool;
    }

    @Override
    public void run(final String... args) throws Exception {
        if (!properties.isEnabled()) {
            return;
        }

        if (!"stdio".equalsIgnoreCase(properties.getTransport())) {
            log.error("Unsupported MCP transport mode [{}]. Only 'stdio' is currently supported.", properties.getTransport());
            throw new IllegalArgumentException("Unsupported MCP transport mode: " + properties.getTransport());
        }

        log.info("Starting Riptide MCP Server Stdio IPC Transport Loop...");

        final var runnerThread = new Thread(() -> {
            // Do NOT close System.in / System.out in try-with-resources
            final var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            final var writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        final JsonRpcMessage request = objectMapper.readValue(line, JsonRpcMessage.class);
                        final JsonRpcMessage response = handleRpcMessage(request);
                        if (response != null) {
                            writer.println(objectMapper.writeValueAsString(response));
                        }
                    } catch (final Exception parseEx) {
                        log.error("Failed to parse MCP JSON-RPC frame: {}", parseEx.getMessage());
                        final JsonRpcMessage parseErrorResponse = JsonRpcMessage.createError(null, -32700, "Parse error: " + parseEx.getMessage());
                        writer.println(objectMapper.writeValueAsString(parseErrorResponse));
                    }
                }
            } catch (final Exception e) {
                log.error("Riptide MCP Stdio transport loop encountered an error", e);
            }
        }, "mcp-stdio-runner");

        runnerThread.setDaemon(true);
        runnerThread.start();
    }

    public JsonRpcMessage handleRpcMessage(final JsonRpcMessage request) {
        if (request == null || request.getMethod() == null) {
            return JsonRpcMessage.createError(null, -32600, "Invalid Request");
        }

        final Object id = request.getId();
        final String method = request.getMethod();
        final boolean isNotification = (id == null) || method.startsWith("notifications/");

        // Check authentication if required
        if (authService.isAuthRequired()) {
            final String authToken = extractAuthToken(request);
            if (!authService.authenticate(authToken)) {
                if (isNotification) {
                    return null;
                }
                return JsonRpcMessage.createError(id, -32001, "Unauthorized: invalid MCP authentication token");
            }
        }

        // JSON-RPC 2.0: Do not reply to notifications
        if (method.startsWith("notifications/")) {
            return null;
        }

        switch (method) {
            case "initialize":
                final Map<String, Object> serverInfo = new LinkedHashMap<>();
                serverInfo.put("protocolVersion", "2024-11-05");
                serverInfo.put("capabilities", Map.of("tools", Map.of(), "prompts", Map.of(), "resources", Map.of()));
                serverInfo.put("serverInfo", Map.of("name", "riptide-flows-mcp", "version", "0.7.2-SNAPSHOT"));
                return isNotification ? null : JsonRpcMessage.createResult(id, serverInfo);

            case "tools/list":
                final List<Map<String, Object>> tools = new ArrayList<>();
                tools.add(topTalkersTool.getDefinition().toMap());
                tools.add(interfaceUtilizationTool.getDefinition().toMap());
                tools.add(hostTraceTool.getDefinition().toMap());
                tools.add(geoAsnTool.getDefinition().toMap());
                tools.add(trafficSpikesTool.getDefinition().toMap());
                tools.add(autoMitigationRulesTool.getDefinition().toMap());
                return isNotification ? null : JsonRpcMessage.createResult(id, Map.of("tools", tools));

            case "tools/call":
                final JsonRpcMessage toolResult = handleToolCall(id, request.getParams());
                return isNotification ? null : toolResult;

            case "prompts/list":
                return isNotification ? null : JsonRpcMessage.createResult(id, Map.of("prompts", skillRegistry.getMcpPrompts()));

            case "prompts/get":
                final String promptName = String.valueOf(request.getParams() != null ? request.getParams().get("name") : "");
                final var skillOpt = skillRegistry.getSkill(promptName);
                if (skillOpt.isPresent()) {
                    final var skill = skillOpt.get();
                    return isNotification ? null : JsonRpcMessage.createResult(id, Map.of(
                            "description", skill.getDescription(),
                            "messages", List.of(Map.of("role", "user", "content", Map.of("type", "text", "text", skill.getRawMarkdown())))
                    ));
                }
                return isNotification ? null : JsonRpcMessage.createError(id, -32602, "Prompt not found: " + promptName);

            case "resources/list":
                return isNotification ? null : JsonRpcMessage.createResult(id, Map.of("resources", skillRegistry.getMcpResources()));

            case "resources/read":
                final String uri = String.valueOf(request.getParams() != null ? request.getParams().get("uri") : "");
                final String resName = uri.replace("resource://riptide/skills/", "");
                final var resOpt = skillRegistry.getSkill(resName);
                if (resOpt.isPresent()) {
                    return isNotification ? null : JsonRpcMessage.createResult(id, Map.of("contents", List.of(Map.of(
                            "uri", uri,
                            "mimeType", "text/markdown",
                            "text", resOpt.get().getRawMarkdown()
                    ))));
                }
                return isNotification ? null : JsonRpcMessage.createError(id, -32602, "Resource not found: " + uri);

            default:
                return isNotification ? null : JsonRpcMessage.createError(id, -32601, "Method not found: " + method);
        }
    }

    private String extractAuthToken(final JsonRpcMessage request) {
        if (request.getParams() != null && request.getParams().containsKey("_meta")) {
            final Object metaObj = request.getParams().get("_meta");
            if (metaObj instanceof Map<?, ?> metaMap && metaMap.containsKey("authToken")) {
                return String.valueOf(metaMap.get("authToken"));
            }
        }
        return null;
    }

    private JsonRpcMessage handleToolCall(final Object id, final Map<String, Object> params) {
        if (params == null || !params.containsKey("name")) {
            return JsonRpcMessage.createError(id, -32602, "Missing tool name in params");
        }

        final String toolName = String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        List<Map<String, Object>> resultData;

        switch (toolName) {
            case "riptide_get_top_talkers":
                resultData = topTalkersTool.execute(arguments);
                break;
            case "riptide_get_interface_utilization":
                resultData = interfaceUtilizationTool.execute(arguments);
                break;
            case "riptide_trace_host_flow":
                resultData = hostTraceTool.execute(arguments);
                break;
            case "riptide_get_geo_asn_distribution":
                resultData = geoAsnTool.execute(arguments);
                break;
            case "riptide_detect_traffic_spikes":
                resultData = trafficSpikesTool.execute(arguments);
                break;
            case "riptide_generate_mitigation_rules":
                resultData = autoMitigationRulesTool.execute(arguments);
                break;
            default:
                return JsonRpcMessage.createError(id, -32601, "Tool not found: " + toolName);
        }

        return JsonRpcMessage.createResult(id, Map.of("content", List.of(Map.of("type", "text", "text", resultData))));
    }
}
