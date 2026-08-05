/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.skills;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility to parse Markdown frontmatter (YAML block) and content into a {@link SkillModel}.
 */
public final class SkillParser {

    private SkillParser() {
        // Utility class
    }

    private static final Pattern NAME_PATTERN = Pattern.compile("^name:\\s*\"?([^\"]+)\"?");
    private static final Pattern DESC_PATTERN = Pattern.compile("^description:\\s*\"?([^\"]+)\"?");
    private static final Pattern CMD_PATTERN = Pattern.compile("^slash_command:\\s*\"?([^\"]+)\"?");

    public static SkillModel parse(final String defaultName, final String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return SkillModel.builder()
                    .name(defaultName)
                    .description("Riptide Agent Skill")
                    .rawMarkdown("")
                    .build();
        }

        String name = defaultName;
        String description = "Riptide Agent Skill";
        String slashCommand = null;

        try (var reader = new BufferedReader(new StringReader(markdownContent))) {
            String line = reader.readLine();
            if (line != null && line.trim().equals("---")) {
                while ((line = reader.readLine()) != null) {
                    if (line.trim().equals("---")) {
                        break;
                    }
                    final Matcher nameMatcher = NAME_PATTERN.matcher(line.trim());
                    if (nameMatcher.find()) {
                        name = nameMatcher.group(1).trim();
                    }
                    final Matcher descMatcher = DESC_PATTERN.matcher(line.trim());
                    if (descMatcher.find()) {
                        description = descMatcher.group(1).trim();
                    }
                    final Matcher cmdMatcher = CMD_PATTERN.matcher(line.trim());
                    if (cmdMatcher.find()) {
                        slashCommand = cmdMatcher.group(1).trim();
                    }
                }
            }
        } catch (final IOException ignored) {
            // Fallback to defaults if parsing frontmatter fails
        }

        return SkillModel.builder()
                .name(name)
                .description(description)
                .slashCommand(slashCommand)
                .rawMarkdown(markdownContent)
                .build();
    }
}
