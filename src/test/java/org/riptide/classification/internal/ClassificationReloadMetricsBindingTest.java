/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper's reload metrics have to land in the registry the process actually exports. Unit tests hand it a
 * registry of their own, so nothing there can tell the exported bean from a fresh {@code new MetricRegistry()} in
 * the bean factory — and with a fresh one the alert this repo's docs tell operators to build would have no series
 * at all.
 * <p>
 * Unlike {@code config.reload.stale} / {@code inventory.reload.stale} (#539, absent while reloading is disabled),
 * the stale gauge here is registered unconditionally: it claims only "the last reload attempt failed and has not
 * recovered", so a 0 is simply true when no reload has run — it makes no claim about the file on disk that a
 * constant 0 could falsify.
 */
@SpringBootTest
class ClassificationReloadMetricsBindingTest {

    @Autowired
    private MetricRegistry metrics;

    @Test
    void theEngineRegistersItsReloadMetricsInTheExportedRegistry() {
        assertThat(this.metrics.getCounters().keySet())
                .contains("classification.reload.successes", "classification.reload.failures");
        assertThat(this.metrics.getGauges()).containsKey("classification.reload.stale");
        assertThat(this.metrics.getGauges().get("classification.reload.stale").getValue())
                .as("the startup load succeeded, so nothing is stale")
                .isEqualTo(0);
    }
}
