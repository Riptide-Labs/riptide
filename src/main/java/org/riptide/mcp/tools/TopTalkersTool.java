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

/**
 * MCP tool for querying top network talkers grouped by application, host, or protocol.
 */
@Component
public class TopTalkersTool {

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
                                "group_by", Map.of("type", "string", "description", "Dimension to group by (application, srcAddr, dstAddr, protocol)")
                        ),
                        "required", List.of("time_range_minutes", "group_by")
                ))
                .build();
    }

    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final int timeRange = ((Number) params.getOrDefault("time_range_minutes", 15)).intValue();
        final String groupBy = String.valueOf(params.getOrDefault("group_by", "application"));
        final String db = mcpService.getDatabaseName();

        final String table = QueryRouter.resolveApplicationTable(db, timeRange);
        final String sql = String.format(
                "SELECT %s, SUM(bytes) AS total_bytes, SUM(packets) AS total_packets FROM %s WHERE timestamp >= now() - INTERVAL %d MINUTE GROUP BY %s ORDER BY total_bytes DESC LIMIT 20",
                groupBy, table, timeRange, groupBy
        );

        return mcpService.executeQuery(sql);
    }
}
