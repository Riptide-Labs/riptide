/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one JSON mapper the MCP components share.
 *
 * <p>Declared rather than auto-configured: this application depends on {@code jackson-databind}
 * directly, not on {@code spring-boot-starter-json}, so Spring Boot contributes no
 * {@code ObjectMapper} bean and every component that wanted one used to construct its own. Should
 * the Jackson starter ever be added, its auto-configured mapper wins and this one steps aside.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnMcpEnabled
public class McpJacksonConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper mcpObjectMapper() {
        return new ObjectMapper();
    }
}
