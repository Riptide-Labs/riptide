/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.skills;

import lombok.extern.slf4j.Slf4j;
import org.riptide.mcp.config.ConditionalOnMcpEnabled;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads embedded Markdown skills from classpath resources (src/main/resources/mcp/skills/*.md)
 * and registers them for MCP Prompts (prompts/list) and Resources (resources/list).
 */
@Slf4j
@ConditionalOnMcpEnabled
@Component
public class SkillRegistry {

    private final Map<String, SkillModel> skills = new LinkedHashMap<>();

    public SkillRegistry() {
        loadEmbeddedSkills();
    }

    private void loadEmbeddedSkills() {
        try {
            final var resolver = new PathMatchingResourcePatternResolver();
            final Resource[] resources = resolver.getResources("classpath*:mcp/skills/*.md");

            for (final Resource res : resources) {
                try (var is = res.getInputStream()) {
                    final String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    final String filename = Objects.requireNonNull(res.getFilename());
                    final String skillKey = filename.replaceAll("^[0-9]+-", "").replace(".md", "");

                    final SkillModel skill = SkillParser.parse(skillKey, content);
                    skills.put(skill.getName(), skill);
                    log.info("Registered Riptide MCP Agent Skill: [{}]", skill.getName());
                }
            }
        } catch (final Exception e) {
            log.error("Failed to load embedded Riptide MCP skills from classpath: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getMcpPrompts() {
        final List<Map<String, Object>> promptList = new ArrayList<>();
        for (final SkillModel skill : skills.values()) {
            promptList.add(skill.toMcpPromptDefinition());
        }
        return promptList;
    }

    public List<Map<String, Object>> getMcpResources() {
        final List<Map<String, Object>> resourceList = new ArrayList<>();
        for (final SkillModel skill : skills.values()) {
            resourceList.add(skill.toMcpResourceDefinition());
        }
        return resourceList;
    }

    public Optional<SkillModel> getSkill(final String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Map<String, SkillModel> getAllSkills() {
        return Collections.unmodifiableMap(skills);
    }
}
