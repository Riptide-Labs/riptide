/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.riptide.mcp.config.ConditionalOnMcpEnabled;
import org.riptide.mcp.protocol.McpToolDefinition;
import org.riptide.mcp.service.QueryRouter;
import org.riptide.mcp.service.RiptideMcpService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tool for querying traffic distribution by Autonomous System Number (ASN) and geographic country.
 */
@ConditionalOnMcpEnabled
@Component
public class GeoAsnTool implements McpTool {

    private final RiptideMcpService mcpService;

    public GeoAsnTool(final RiptideMcpService mcpService) {
        this.mcpService = mcpService;
    }

    @Override
    public McpToolDefinition getDefinition() {
        return McpToolDefinition.builder()
                .name("riptide_get_geo_asn_distribution")
                .description("Queries egress and ingress traffic volume aggregated by ASNs and origin/destination countries.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "time_range_minutes", Map.of("type", "integer", "description", "Time range in minutes")
                        ),
                        "required", List.of("time_range_minutes")
                ))
                .build();
    }

    @Override
    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final Map<String, Object> safeParams = ToolParams.safe(params);
        final int timeRange = ToolParams.timeRangeMinutes(safeParams.get("time_range_minutes"), 60);
        final String db = mcpService.getDatabaseName();

        final String table = QueryRouter.resolveGeoAsnTable(db, timeRange);
        final String sql = String.format(
                "SELECT dstAs, dstCountry, SUM(bytes) AS total_bytes FROM %s WHERE timestamp >= now() - INTERVAL %d MINUTE GROUP BY dstAs, dstCountry ORDER BY total_bytes DESC LIMIT 20",
                table, timeRange
        );

        return mcpService.executeRangeQuery(sql, table, timeRange);
    }
}
