/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.riptide.mcp.protocol.McpToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Common interface for Riptide MCP tools.
 */
public interface McpTool {

    /**
     * Returns the tool definition and input schema.
     */
    McpToolDefinition getDefinition();

    /**
     * Executes the tool with the provided arguments map.
     */
    List<Map<String, Object>> execute(Map<String, Object> params);
}
