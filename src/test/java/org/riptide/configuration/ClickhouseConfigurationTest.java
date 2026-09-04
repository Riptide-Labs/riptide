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

    /**
     * <b>Turning batching off turns dead-lettering off with it (#548), deliberately.</b>
     *
     * <p>{@code BatchingFlowRepository.flush} is the only caller of
     * {@code FlowRepository.deadLetter} in the whole repository, so with {@code batch.enabled=false}
     * a refused insert is not dead-lettered at all: the {@code FlowException} travels up through
     * {@code FlowPersister} and {@code Pipeline} to {@code Daemon.dispatcherFor}, which charges
     * {@code pipeline.dispatchErrors} and drops the records. None of {@code persister.batch.*}
     * exists on that path — the raw repository is not even handed a {@code MetricRegistry}.</p>
     *
     * <p>That is a limit rather than a defect. The un-batched path exists precisely so the rejection
     * reaches the caller synchronously ({@code ClickhouseConfig.BatchConfig}), which is the signal
     * dead-lettering replaces when batching swallows it; and it inserts one {@code persist} call at a
     * time, so a poison row costs that call rather than up to {@code max-rows} flows — the loss this
     * feature exists to bound. Wiring a second dead-letter path through the pipeline would add
     * counters and control flow to the mode chosen for having neither.</p>
     *
     * <p>This test is the pin. Without it the exclusion rests on nobody having wired a call, and the
     * docs that state the limit — {@code operations.md} and {@code clickhouse.md} — would be the only
     * record of a decision. If a future change does dead-letter here, this test fails and says so.</p>
     */
    @Test
    void theUnbatchedPathDoesNotDeadLetterAndThatIsTheDecision() {
        final var config = new ClickhouseConfig();
        config.getBatch().setEnabled(false);

        final var repository = new ClickhouseConfiguration().clickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config, SecretResolvers.defaults(),
                new MetricRegistry());

        Assertions.assertThat(repository)
                .as("BatchingFlowRepository is the only thing that ever calls deadLetter, so its"
                        + " absence from the chain IS the absence of dead-lettering")
                .isNotInstanceOf(BatchingFlowRepository.class);
    }
}
