/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.riptide.mcp.protocol.McpToolDefinition;
import org.riptide.mcp.service.RiptideMcpService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP tool for performing forensic host conversation walks for a given IP address.
 */
@Component
public class HostTraceTool {

    private final RiptideMcpService mcpService;

    public HostTraceTool(final RiptideMcpService mcpService) {
        this.mcpService = mcpService;
    }

    public McpToolDefinition getDefinition() {
        return McpToolDefinition.builder()
                .name("riptide_trace_host_flow")
                .description("Traces host conversations, ports, bytes, and protocols for a target IP address.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "ip_address", Map.of("type", "string", "description", "Target IP address to trace"),
                                "time_range_minutes", Map.of("type", "integer", "description", "Time range in minutes")
                        ),
                        "required", List.of("ip_address")
                ))
                .build();
    }

    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final String ip = String.valueOf(params.get("ip_address"));
        final int timeRange = ((Number) params.getOrDefault("time_range_minutes", 15)).intValue();
        final String db = mcpService.getDatabaseName();

        final String sql = String.format(
                "SELECT srcAddr, dstAddr, srcPort, dstPort, protocol, application, tcpFlags, bytes, packets FROM %s.flows WHERE timestamp >= now() - INTERVAL %d MINUTE AND (srcAddr = '%s' OR dstAddr = '%s') ORDER BY bytes DESC LIMIT 50",
                db, timeRange, ip, ip
        );

        return mcpService.executeQuery(sql);
    }
}
