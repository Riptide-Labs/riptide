/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.classification.internal.ClassificationRuleReloader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer side of {@code riptide.classification.reload-interval} (#655): a key
 * nothing reads is a key that silently does nothing, and it would be found by an operator
 * rather than by CI.
 *
 * <p>Bound through the real context and read back through the schedule it is supposed to
 * start: {@code classification.reload.dead} is registered only past the interval gate, so
 * its presence here is the property, not the bound value. {@code ReloaderDisabledMetricsTest}
 * is the other half — the same gauge is absent with no interval set.</p>
 */
@SpringBootTest(properties = "riptide.classification.reload-interval=1h")
class ClassificationReloadIntervalTest {

    @Autowired
    private MetricRegistry metrics;

    @Autowired
    private ClassificationConfig config;

    @Test
    void theIntervalIsBoundAndStartsTheReloadSchedule() {
        assertThat(this.config.getReloadInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(this.metrics.getGauges())
                .as("the schedule exists, so it publishes whether it is still alive")
                .containsKey("classification.reload.dead");
        assertThat(this.metrics.getGauges().get("classification.reload.dead").getValue())
                .as("the schedule is alive")
                .isEqualTo(0);
    }

    /**
     * The stale gauge in the exported registry has to be the reloader's OR of both halves,
     * not the engine's own. That ordering rests entirely on the reloader bean taking the
     * engine as a constructor parameter, so it is asserted in the real context rather than
     * only where a test wires the two objects itself in the intended order.
     */
    @Test
    void theStaleGaugeIsTheReloadersOrOfBothHalves() {
        final Gauge<?> registered = this.metrics.getGauges().get("classification.reload.stale");
        assertThat(registered).as("the stale gauge is registered").isNotNull();
        // the lambda's own class, which carries the class that defined it: the engine
        // registers one of its own in its constructor, and this asserts that the reloader
        // came second and replaced it
        assertThat(registered.getClass().getName())
                .as("the engine's gauge would name AsyncReloadingClassificationEngine here")
                .startsWith(ClassificationRuleReloader.class.getName());
        assertThat(registered.getValue()).as("the startup load succeeded and the source is reachable")
                .isEqualTo(0);
    }
}
