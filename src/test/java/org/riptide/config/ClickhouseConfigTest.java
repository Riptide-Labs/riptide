/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

/**
 * The {@code asyncInserts} derivation: batching supersedes server-side coalescing, but only
 * while batching is actually enabled — with it off the pre-batching manage-mode default applies,
 * so a {@code batch.enabled=false} config does not silently land on the slowest combination.
 *
 * <p>And the {@code startupWait} default (#833): 30 s, chosen to sit under the compose healthcheck
 * and Kubernetes startupProbe budgets the docs state. The negative-value rejection and the read of
 * the key live where the value is consumed: {@code StartupWaitTest} and
 * {@code ClickhouseStartupWaitIT}.</p>
 */
class ClickhouseConfigTest {

    @Test
    void startupWaitDefaultsToThirtySeconds() {
        Assertions.assertThat(new ClickhouseConfig().getStartupWait()).isEqualTo(Duration.ofSeconds(30));
    }

    /** At 40,000 a burst of synchronised exports plus dashboard reads dropped rows (#945). */
    @Test
    void batchQueueCapacityDefaultsToEightyThousandRows() {
        Assertions.assertThat(new ClickhouseConfig().getBatch().getQueueCapacity()).isEqualTo(80_000);
    }

    /** Doubled with the queue: a full 80,000-row queue does not drain inside 5 s at a slow ClickHouse. */
    @Test
    void shutdownGracePeriodDefaultsToTenSeconds() {
        Assertions.assertThat(new ClickhouseConfig().getBatch().getShutdownGracePeriod()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void asyncInsertsAreOffWhileBatchingIsEnabled() {
        final var config = new ClickhouseConfig();
        config.setManageSchema(true);

        // Batching is on by default and supersedes coalescing — even in manage mode.
        Assertions.assertThat(config.getBatch().isEnabled()).isTrue();
        Assertions.assertThat(config.isAsyncInserts()).isFalse();
    }

    @Test
    void asyncInsertsFallBackToManageModeWhenBatchingIsDisabled() {
        final var config = new ClickhouseConfig();
        config.getBatch().setEnabled(false);

        config.setManageSchema(true);
        Assertions.assertThat(config.isAsyncInserts()).isTrue();

        // Provisioned mode keeps the synchronous CHECK-barrier rejection.
        config.setManageSchema(false);
        Assertions.assertThat(config.isAsyncInserts()).isFalse();
    }

    @Test
    void batchFlushersDefaultsToOne() {
        Assertions.assertThat(new ClickhouseConfig().getBatch().getFlushers()).isEqualTo(1);
    }

    /** The key's consumer is BatchingFlowRepository; this proves the key reaches the config it reads. */
    @Test
    void batchFlushersBindsFromItsKey() {
        final ClickhouseConfig bound = new Binder(new MapConfigurationPropertySource(
                Map.of("riptide.clickhouse.batch.flushers", "3")))
                .bind("riptide.clickhouse", ClickhouseConfig.class)
                .get();
        Assertions.assertThat(bound.getBatch().getFlushers()).isEqualTo(3);
    }

    @Test
    void explicitAsyncInsertsWinsOverEitherDerivation() {
        final var config = new ClickhouseConfig();

        // Explicitly on, against the batching-enabled derivation.
        config.setAsyncInserts(true);
        Assertions.assertThat(config.isAsyncInserts()).isTrue();

        // Explicitly off, against the batching-disabled manage-mode derivation.
        config.setAsyncInserts(false);
        config.getBatch().setEnabled(false);
        config.setManageSchema(true);
        Assertions.assertThat(config.isAsyncInserts()).isFalse();
    }
}
