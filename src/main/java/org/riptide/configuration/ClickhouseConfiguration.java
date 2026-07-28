/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

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
