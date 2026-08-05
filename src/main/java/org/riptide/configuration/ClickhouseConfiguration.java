/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import com.clickhouse.client.api.Client;
import com.codahale.metrics.MetricRegistry;
import org.riptide.config.ClickhouseConfig;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.clickhouse.BatchingFlowRepository;
import org.riptide.repository.clickhouse.ClickhouseRepository;
import org.riptide.secrets.SecretResolvers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClickhouseConfiguration {

    @Bean
    public Client clickhouseClient(final ClickhouseConfig config,
                                   final SecretResolvers secretResolvers) {
        final String resolvedUsername = secretResolvers.resolve(config.getUsername());
        final String resolvedPassword = secretResolvers.resolve(config.getPassword());
        final String username = resolvedUsername != null ? resolvedUsername : "default";
        final String password = resolvedPassword != null ? resolvedPassword : "";

        final var builder = new Client.Builder()
                .addEndpoint(config.getEndpoint())
                .setUsername(username)
                .setPassword(password)
                .setDefaultDatabase(config.getDatabase())
                .compressClientRequest(config.isCompressRequests())
                .compressServerResponse(true);
        if (config.isAsyncInserts()) {
            builder.serverSetting("async_insert", "1")
                    .serverSetting("wait_for_async_insert", "0");
        }
        return builder.build();
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
