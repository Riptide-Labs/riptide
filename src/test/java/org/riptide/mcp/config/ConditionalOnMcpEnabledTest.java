/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.config;

import org.junit.jupiter.api.Test;
import org.riptide.mcp.auth.McpAuthProperties;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.skills.SkillRegistry;
import org.riptide.secrets.SecretResolvers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP components resolve secrets and scan the classpath in their constructors, so a collector
 * that never speaks MCP must not create them at all.
 */
class ConditionalOnMcpEnabledTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpBeans.class);

    @Test
    void createsMcpBeansWhenEnabled() {
        contextRunner.withPropertyValues("riptide.mcp.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpAuthService.class);
                    assertThat(context).hasSingleBean(SkillRegistry.class);
                });
    }

    @Test
    void createsNoMcpBeansWhenDisabled() {
        contextRunner.withPropertyValues("riptide.mcp.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(McpAuthService.class);
                    assertThat(context).doesNotHaveBean(SkillRegistry.class);
                });
    }

    /** Off is the default: an unset property must not pay the startup cost either. */
    @Test
    void createsNoMcpBeansWhenUnset() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(McpAuthService.class);
            assertThat(context).doesNotHaveBean(SkillRegistry.class);
        });
    }

    /**
     * Mirrors how the components are declared: the meta-annotation gates them, and this asserts the
     * gate, not the component scan.
     */
    @Configuration(proxyBeanMethods = false)
    static class McpBeans {

        @Bean
        @ConditionalOnMcpEnabled
        McpAuthService mcpAuthService() {
            return new McpAuthService(new McpAuthProperties(), SecretResolvers.defaults());
        }

        @Bean
        @ConditionalOnMcpEnabled
        SkillRegistry skillRegistry() {
            return new SkillRegistry();
        }
    }
}
