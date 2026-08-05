/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.skills;

import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of an auto-shipped Riptide Agent Skill.
 */
@Data
@Builder
public class SkillModel {

    private final String name;
    private final String description;
    private final String slashCommand;
    private final String rawMarkdown;

    /**
     * Converts this skill to an MCP Prompt definition schema for prompts/list.
     */
    public Map<String, Object> toMcpPromptDefinition() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description + (slashCommand != null ? " [Command: " + slashCommand + "]" : ""));
        map.put("arguments", List.of());
        return map;
    }

    /**
     * Converts this skill to an MCP Resource definition schema for resources/list.
     */
    public Map<String, Object> toMcpResourceDefinition() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("uri", "resource://riptide/skills/" + name);
        map.put("name", name);
        map.put("description", description);
        map.put("mimeType", "text/markdown");
        return map;
    }
}
