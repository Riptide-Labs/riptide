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
 *
 * <p>The classification rules reloader (#655) joins the same rule with its own key,
 * {@code riptide.classification.reload-interval}, but only for the dead gauge: its stale
 * gauge is the engine's, registered unconditionally because it claims only "the last load
 * attempt failed", which a constant 0 cannot falsify. A dead gauge with no schedule
 * behind it would claim there is one.</p>
 */
@SpringBootTest
class ReloaderDisabledMetricsTest {

    @Autowired
    private MetricRegistry metrics;

    @Test
    void disabledReloadersPublishNoGauges() {
        assertThat(metrics.getGauges()).doesNotContainKeys(
                "config.reload.stale", "config.reload.dead",
                "inventory.reload.stale", "inventory.reload.dead",
                "classification.reload.dead");
        assertThat(metrics.getGauges())
                .as("the classification stale gauge is the engine's and claims only that a load failed, "
                        + "which a constant 0 cannot falsify — so it stays, and the paragraph above says so")
                .containsKey("classification.reload.stale");
        assertThat(metrics.getCounters().keySet())
                .contains("config.reload.successes", "config.reload.failures",
                        "inventory.reload.successes", "inventory.reload.failures",
                        "classification.reload.successes", "classification.reload.failures");
    }
}
