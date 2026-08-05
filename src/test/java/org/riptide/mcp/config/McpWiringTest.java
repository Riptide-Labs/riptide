/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.mcp.auth.McpAuthProperties;
import org.riptide.mcp.auth.McpAuthService;
import org.riptide.mcp.service.McpMessageHandler;
import org.riptide.mcp.service.RiptideMcpService;
import org.riptide.mcp.skills.SkillRegistry;
import org.riptide.mcp.tools.McpTool;
import org.riptide.mcp.transport.McpSseServer;
import org.riptide.mcp.transport.McpStdioRunner;
import org.riptide.secrets.SecretResolvers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires the real MCP components through a Spring context.
 *
 * <p>The component tests construct these classes directly, which cannot catch a dependency the
 * container has no bean for: this application pulls in {@code jackson-databind} without the Jackson
 * starter, so nothing auto-configures an {@code ObjectMapper} and the context failed to start even
 * though every unit test passed. This asserts the graph actually resolves.
 */
class McpWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpTestConfiguration.class);

    @Test
    void wiresTheMcpComponentGraphWhenEnabled() {
        contextRunner.withPropertyValues("riptide.mcp.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpMessageHandler.class);
            assertThat(context).hasSingleBean(RiptideMcpService.class);
            assertThat(context).hasSingleBean(McpAuthService.class);
            assertThat(context).hasSingleBean(SkillRegistry.class);
            assertThat(context).hasSingleBean(ObjectMapper.class);
            assertThat(context.getBeansOfType(McpTool.class)).hasSize(6);
        });
    }

    /** The transports are beans in their own right; only the configured one goes on to listen. */
    @Test
    void wiresBothTransportsWhenEnabled() {
        contextRunner.withPropertyValues("riptide.mcp.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(McpSseServer.class);
            assertThat(context).hasSingleBean(McpStdioRunner.class);
        });
    }

    @Test
    void wiresNothingWhenDisabled() {
        contextRunner.withPropertyValues("riptide.mcp.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(McpMessageHandler.class);
            assertThat(context).doesNotHaveBean(RiptideMcpService.class);
            assertThat(context).doesNotHaveBean(McpSseServer.class);
            assertThat(context).doesNotHaveBean(McpStdioRunner.class);
            assertThat(context).doesNotHaveBean(ObjectMapper.class);
            assertThat(context.getBeansOfType(McpTool.class)).isEmpty();
        });
    }

    /**
     * The MCP packages plus the collaborators the daemon would otherwise supply. The ClickHouse
     * client is deliberately absent: it is optional on {@link RiptideMcpService}, so the graph must
     * resolve without a reachable database.
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "org.riptide.mcp")
    @EnableConfigurationProperties({McpProperties.class, McpAuthProperties.class, ClickhouseConfig.class})
    static class McpTestConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SecretResolvers secretResolvers() {
            return SecretResolvers.defaults();
        }
    }
}
