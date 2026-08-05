/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Main configuration properties for the native Model Context Protocol (MCP) server component.
 */
@Data
@ConfigurationProperties(prefix = "riptide.mcp")
public class McpProperties {

    /**
     * Whether the MCP server component is enabled.
     */
    private boolean enabled = false;

    /**
     * Transport mechanism for MCP communication ("stdio" or "sse").
     */
    private String transport = "stdio";

    /**
     * Maximum execution time in seconds for ClickHouse MCP queries.
     */
    private int queryTimeoutSeconds = 5;

    /**
     * Maximum number of rows returned by MCP flow queries.
     */
    private int maxResultRows = 50;
}
