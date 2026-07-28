/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import com.codahale.metrics.MetricRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.repository.clickhouse.BatchingFlowRepository;
import org.riptide.repository.clickhouse.ClickhouseRepository;
import org.riptide.repository.clickhouse.ClickhouseRepository$FlowMapperImpl;
import org.riptide.secrets.SecretResolvers;

/**
 * Wiring test for the repository bean: the batch.enabled flag decides between the batching
 * decorator and the raw per-record repository. Constructing a ClickhouseRepository performs no
 * I/O (the client only connects on start()/persist()), so this runs without a server.
 */
class ClickhouseConfigurationTest {

    @Test
    void beanIsTheBatchingDecoratorWhenBatchingIsEnabled() {
        final var repository = new ClickhouseConfiguration().clickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), new ClickhouseConfig(), SecretResolvers.defaults(),
                new MetricRegistry());

        Assertions.assertThat(repository).isInstanceOf(BatchingFlowRepository.class);
    }

    @Test
    void beanIsTheRawRepositoryWhenBatchingIsDisabled() {
        final var config = new ClickhouseConfig();
        config.getBatch().setEnabled(false);

        final var repository = new ClickhouseConfiguration().clickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config, SecretResolvers.defaults(),
                new MetricRegistry());

        Assertions.assertThat(repository).isInstanceOf(ClickhouseRepository.class);
    }
}
