/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #539: with reloading disabled (the default: {@code riptide.config.reload-interval}
 * unset), the stale and dead gauges must be ABSENT, not constant. A constant 0 read as
 * "the running config matches the file" for a file that is never read again; absence is
 * the honest disabled signal. The counters stay registered — a zero counter is true.
 */
@SpringBootTest
class ReloaderDisabledMetricsTest {

    @Autowired
    private MetricRegistry metrics;

    @Test
    void disabledReloadersPublishNoGauges() {
        assertThat(metrics.getGauges()).doesNotContainKeys(
                "config.reload.stale", "config.reload.dead",
                "inventory.reload.stale", "inventory.reload.dead");
        assertThat(metrics.getCounters().keySet())
                .contains("config.reload.successes", "config.reload.failures",
                        "inventory.reload.successes", "inventory.reload.failures");
    }
}
