/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.riptide.mcp.protocol.McpToolDefinition;
import org.riptide.mcp.service.QueryRouter;
import org.riptide.mcp.service.RiptideMcpService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool for querying top network talkers grouped by application, host, or protocol.
 */
@Component
public class TopTalkersTool {

    private static final Set<String> ALLOWED_GROUP_BY = Set.of(
            "application", "protocol", "srcAddr", "dstAddr", "srcAs", "dstAs"
    );

    private final RiptideMcpService mcpService;

    public TopTalkersTool(final RiptideMcpService mcpService) {
        this.mcpService = mcpService;
    }

    public McpToolDefinition getDefinition() {
        return McpToolDefinition.builder()
                .name("riptide_get_top_talkers")
                .description("Queries top talkers grouped by application, host, or protocol over a given time range.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "time_range_minutes", Map.of("type", "integer", "description", "Time range in minutes"),
                                "group_by", Map.of("type", "string", "description", "Dimension to group by (application, srcAddr, dstAddr, protocol, srcAs, dstAs)")
                        ),
                        "required", List.of("time_range_minutes", "group_by")
                ))
                .build();
    }

    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final String rawGroupBy = String.valueOf(params.getOrDefault("group_by", "application"));
        if (!ALLOWED_GROUP_BY.contains(rawGroupBy)) {
            return List.of(Map.of("error", "Invalid group_by dimension. Allowed: " + ALLOWED_GROUP_BY));
        }

        final int timeRange = Math.min(Math.max(1, ((Number) params.getOrDefault("time_range_minutes", 15)).intValue()), 43200);
        final String db = mcpService.getDatabaseName();
        final String table = QueryRouter.resolveTopTalkersTable(db, timeRange, rawGroupBy);

        final String sql = String.format(
                "SELECT %s, SUM(bytes) AS total_bytes, SUM(packets) AS total_packets FROM %s WHERE timestamp >= now() - INTERVAL %d MINUTE GROUP BY %s ORDER BY total_bytes DESC LIMIT 20",
                rawGroupBy, table, timeRange, rawGroupBy
        );

        return mcpService.executeQuery(sql);
    }
}
