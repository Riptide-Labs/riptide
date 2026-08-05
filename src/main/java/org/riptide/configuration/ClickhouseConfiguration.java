/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import com.clickhouse.client.api.Client;
import com.codahale.metrics.MetricRegistry;
import org.riptide.config.ClickhouseConfig;
import org.riptide.mcp.config.McpProperties;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.clickhouse.BatchingFlowRepository;
import org.riptide.repository.clickhouse.ClickhouseRepository;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClickhouseConfiguration {

    /**
     * The MCP read path's ClickHouse client. It authenticates as {@code riptide.mcp.clickhouse}
     * when set — the tenant reader in a provisioned deployment — and only falls back to the ingest
     * credentials when it is not, which is the single-tenant case where they are the same identity.
     * No {@code async_insert} settings here: they configure the write path this client never takes.
     */
    @Bean
    @ConditionalOnProperty(name = "riptide.mcp.enabled", havingValue = "true")
    public Client clickhouseClient(final ClickhouseConfig config,
                                   final McpProperties mcpProperties,
                                   final SecretResolvers secretResolvers) {
        // Username and password move together: a reader username paired with the writer's password
        // would just fail to authenticate.
        final boolean useMcpIdentity = mcpProperties.getClickhouse().getUsername() != null;
        final SecretRef usernameRef = useMcpIdentity
                ? mcpProperties.getClickhouse().getUsername() : config.getUsername();
        final SecretRef passwordRef = useMcpIdentity
                ? mcpProperties.getClickhouse().getPassword() : config.getPassword();
        final String resolvedUsername = secretResolvers.resolve(usernameRef);
        final String resolvedPassword = secretResolvers.resolve(passwordRef);
        final String username = resolvedUsername != null ? resolvedUsername : "default";
        final String password = resolvedPassword != null ? resolvedPassword : "";

        return new Client.Builder()
                .addEndpoint(config.getEndpoint())
                .setUsername(username)
                .setPassword(password)
                .setDefaultDatabase(config.getDatabase())
                .compressClientRequest(config.isCompressRequests())
                .compressServerResponse(true)
                .build();
    }

    @Bean
    public FlowRepository clickhouseRepository(final ClickhouseRepository.FlowMapper flowMapper,
                                               final ClickhouseConfig config,
                                               final SecretResolvers secretResolvers,
                                               final MetricRegistry metricRegistry) {
        final var repository = new ClickhouseRepository(flowMapper, config, secretResolvers);
        // The batching decorator is the default write path; disabling it falls back to the raw
        // per-record repository (one insert per persist call).
        if (config.getBatch().isEnabled()) {
            return new BatchingFlowRepository(repository, config.getBatch(), metricRegistry);
        }
        return repository;
    }
}
