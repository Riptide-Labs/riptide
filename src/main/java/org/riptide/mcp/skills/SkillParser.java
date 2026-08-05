/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.skills;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.Map;

/**
 * Parses a skill's Markdown frontmatter (the leading {@code ---} delimited YAML block) and content
 * into a {@link SkillModel}.
 *
 * <p>The block is handed to a YAML parser rather than matched line by line, so a value that is
 * quoted, folded across lines, or contains a colon is read as written instead of being truncated at
 * the first quote. A skill whose frontmatter is malformed still loads: it keeps its filename-derived
 * name and the default description, which is preferable to dropping a skill from the registry over
 * a metadata typo.
 */
public final class SkillParser {

    private static final String DELIMITER = "---";
    private static final String DEFAULT_DESCRIPTION = "Riptide Agent Skill";

    private SkillParser() {
        // Utility class
    }

    public static SkillModel parse(final String defaultName, final String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return SkillModel.builder()
                    .name(defaultName)
                    .description(DEFAULT_DESCRIPTION)
                    .rawMarkdown("")
                    .build();
        }

        final Map<String, Object> frontmatter = parseFrontmatter(markdownContent);

        return SkillModel.builder()
                .name(text(frontmatter, "name", defaultName))
                .description(text(frontmatter, "description", DEFAULT_DESCRIPTION))
                .slashCommand(text(frontmatter, "slash_command", null))
                .rawMarkdown(markdownContent)
                .build();
    }

    /** The leading frontmatter block as a map, or empty when there is none or it does not parse. */
    private static Map<String, Object> parseFrontmatter(final String markdownContent) {
        final String block = extractFrontmatterBlock(markdownContent);
        if (block == null || block.isBlank()) {
            return Map.of();
        }
        try {
            // SafeConstructor: frontmatter is data, and a document must not be able to name a type
            // for the parser to instantiate.
            final Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(block);
            if (loaded instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
        } catch (final RuntimeException e) {
            // Malformed frontmatter falls back to the defaults rather than losing the skill.
            return Map.of();
        }
        return Map.of();
    }

    /** The text between the opening {@code ---} and the next one, or null when unterminated. */
    private static String extractFrontmatterBlock(final String markdownContent) {
        final String normalised = markdownContent.stripLeading();
        if (!normalised.startsWith(DELIMITER)) {
            return null;
        }
        final int bodyStart = normalised.indexOf('\n');
        if (bodyStart < 0) {
            return null;
        }
        final int closing = normalised.indexOf("\n" + DELIMITER, bodyStart);
        if (closing < 0) {
            return null;
        }
        return normalised.substring(bodyStart + 1, closing);
    }

    private static String text(final Map<String, Object> frontmatter, final String key, final String defaultValue) {
        final Object value = frontmatter.get(key);
        if (value == null) {
            return defaultValue;
        }
        final String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }
}
