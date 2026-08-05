/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SkillRegistryTest {

    @Test
    public void loadsAllEmbeddedRiptideSkillsFromClasspath() {
        final SkillRegistry registry = new SkillRegistry();

        assertThat(registry.getAllSkills()).hasSize(7);
        assertThat(registry.getSkill("riptide-ddos-mitigation-triage")).isPresent();
        assertThat(registry.getSkill("riptide-cause-analysis-triage")).isPresent();
        assertThat(registry.getSkill("riptide-interface-capacity-analysis")).isPresent();
        assertThat(registry.getSkill("riptide-peering-geo-analysis")).isPresent();
        assertThat(registry.getSkill("riptide-application-performance-triage")).isPresent();
        assertThat(registry.getSkill("riptide-host-forensic-investigation")).isPresent();
        assertThat(registry.getSkill("riptide-ddos-auto-mitigation-playbook")).isPresent();

        assertThat(registry.getMcpPrompts()).hasSize(7);
        assertThat(registry.getMcpResources()).hasSize(7);
    }
}
