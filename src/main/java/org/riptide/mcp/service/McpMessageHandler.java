/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.protocol.JsonRpcMessage;
import org.riptide.mcp.skills.SkillRegistry;
import org.riptide.mcp.tools.McpTool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core JSON-RPC 2.0 message handler for MCP tools, prompts, resources, and spec protocol methods.
 */
@Slf4j
@Service
public class McpMessageHandler {

    private final McpAuthService authService;
    private final SkillRegistry skillRegistry;
    private final Map<String, McpTool> toolsMap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpMessageHandler(final McpAuthService authService,
                             final SkillRegistry skillRegistry,
                             final List<McpTool> tools) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry must not be null");
        final List<McpTool> safeTools = tools != null ? tools : List.of();
        this.toolsMap = safeTools.stream().collect(Collectors.toMap(
                tool -> tool.getDefinition().getName(),
                Function.identity(),
                (existing, replacement) -> existing
        ));
    }

    public JsonRpcMessage handleRpcMessage(final JsonRpcMessage request) {
        return handleRpcMessage(request, null);
    }

    public JsonRpcMessage handleRpcMessage(final JsonRpcMessage request, final String headerAuthToken) {
        if (request == null || request.getMethod() == null) {
            return JsonRpcMessage.createError(null, -32600, "Invalid Request");
        }

        final Object id = request.getId();
        final String method = request.getMethod();
        final boolean isNotification = (id == null) || method.startsWith("notifications/");

        // Authenticate request if auth is enabled
        if (authService.isAuthRequired()) {
            String authToken = headerAuthToken;
            if (authToken == null || authToken.isBlank()) {
                authToken = extractAuthToken(request);
            }
            if (!authService.authenticate(authToken)) {
                if (isNotification) {
                    return null;
                }
                return JsonRpcMessage.createError(id, -32001, "Unauthorized: invalid MCP authentication token");
            }
        }

        // Standard notifications
        if (method.startsWith("notifications/")) {
            log.debug("Received MCP notification [{}], no response required.", method);
            return null;
        }

        switch (method) {
            case "initialize":
                String protocolVersion = "2024-11-05";
                if (request.getParams() != null && request.getParams().containsKey("protocolVersion")) {
                    final String reqVer = String.valueOf(request.getParams().get("protocolVersion"));
                    if (!reqVer.isBlank()) {
                        protocolVersion = reqVer;
                    }
                }
                final Map<String, Object> serverInfo = new LinkedHashMap<>();
                serverInfo.put("protocolVersion", protocolVersion);
                serverInfo.put("capabilities", Map.of("tools", Map.of(), "prompts", Map.of(), "resources", Map.of()));
                serverInfo.put("serverInfo", Map.of("name", "riptide-flows-mcp", "version", "0.7.2-SNAPSHOT"));
                return isNotification ? null : JsonRpcMessage.createResult(id, serverInfo);

            case "ping":
                return isNotification ? null : JsonRpcMessage.createResult(id, Map.of());

            case "tools/list":
                final List<Map<String, Object>> toolsList = new ArrayList<>();
                for (final McpTool tool : toolsMap.values()) {
                    toolsList.add(tool.getDefinition().toMap());
                }
                return isNotification ? null : JsonRpcMessage.createResult(id, Map.of("tools", toolsList));

            case "tools/call":
                final JsonRpcMessage toolResult = handleToolCall(id, request.getParams());
                return isNotification ? null : toolResult;

            case "prompts/list":
                return isNotification ? null : JsonRpcMessage.createResult(id, Map.of("prompts", skillRegistry.getMcpPrompts()));

            case "prompts/get":
                final Object rawPromptObj = request.getParams() != null ? request.getParams().get("name") : null;
                final String promptName = rawPromptObj != null ? String.valueOf(rawPromptObj) : "";
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
                final Object rawUriObj = request.getParams() != null ? request.getParams().get("uri") : null;
                final String uri = rawUriObj != null ? String.valueOf(rawUriObj) : "";
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

        final McpTool tool = toolsMap.get(toolName);
        if (tool == null) {
            return JsonRpcMessage.createError(id, -32602, "Tool not found: " + toolName);
        }

        try {
            final List<Map<String, Object>> resultData = tool.execute(arguments);
            final String jsonText = objectMapper.writeValueAsString(resultData);
            return JsonRpcMessage.createResult(id, Map.of("content", List.of(Map.of("type", "text", "text", jsonText))));
        } catch (final Exception e) {
            log.error("Execution failed for tool [{}]: {}", toolName, e.getMessage(), e);
            return JsonRpcMessage.createError(id, -32603, "Tool execution error: " + e.getMessage());
        }
    }

    public Map<String, McpTool> getToolsMap() {
        return toolsMap;
    }
}
