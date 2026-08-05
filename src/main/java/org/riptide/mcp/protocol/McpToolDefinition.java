/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.protocol;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool schema definition builder for tools/list responses.
 */
@Data
@Builder
public class McpToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("inputSchema", inputSchema != null ? inputSchema : Map.of("type", "object", "properties", Map.of()));
        return map;
    }
}
