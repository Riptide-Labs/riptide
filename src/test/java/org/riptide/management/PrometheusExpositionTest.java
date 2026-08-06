/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusExpositionTest {

    @Test
    void registryNamesAreSanitisedIntoValidMetricNames() {
        final var registry = new MetricRegistry();
        registry.counter("enrichment.optionInterfaces.consumed").inc();

        // dots are legal in a registry name and illegal in a Prometheus one
        assertThat(PrometheusExposition.render(registry))
                .contains("enrichment_optionInterfaces_consumed 1.0")
                .doesNotContain("enrichment.optionInterfaces");
    }

    @Test
    void aNameStartingWithADigitDoesNotProduceAnInvalidMetric() {
        final var registry = new MetricRegistry();
        registry.counter("1minute.thing").inc();

        // Prometheus names may not begin with a digit, but may contain them
        assertThat(PrometheusExposition.render(registry)).contains("_minute_thing 1.0");
    }

    @Test
    void timerDurationsAreExportedInSecondsNotNanoseconds() {
        final var registry = new MetricRegistry();
        final var timer = registry.timer("snmp.walkDuration");
        timer.update(250, TimeUnit.MILLISECONDS);

        final String rendered = PrometheusExposition.render(registry);

        // Dropwizard snapshots are nanoseconds; Prometheus tooling assumes seconds. A single
        // 250 ms sample must therefore surface as 0.25, not 2.5E8.
        assertThat(rendered)
                .contains("# TYPE snmp_walkDuration_seconds summary")
                .contains("snmp_walkDuration_seconds{quantile=\"0.5\"} 0.25")
                .contains("snmp_walkDuration_seconds_count 1.0");
    }

    @Test
    void nonNumericGaugesAreSkippedRatherThanRenderedAsGarbage() {
        final var registry = new MetricRegistry();
        registry.gauge("build.version", () -> () -> "0.7.1");
        registry.gauge("queue.depth", () -> () -> 42);

        final String rendered = PrometheusExposition.render(registry);

        assertThat(rendered).contains("queue_depth 42.0");
        // a string-valued gauge has no Prometheus representation; emitting the name with an
        // unparseable value would break the whole scrape, not just that series
        assertThat(rendered).doesNotContain("build_version");
    }

    @Test
    void metersExportBothTheCountAndTheirMovingRates() {
        final var registry = new MetricRegistry();
        registry.meter("snmp.walks").mark(5);

        assertThat(PrometheusExposition.render(registry))
                .contains("# TYPE snmp_walks counter")
                .contains("snmp_walks 5.0")
                .contains("# TYPE snmp_walks_rate_1m gauge")
                .contains("# TYPE snmp_walks_rate_5m gauge");
    }

    @Test
    void anEmptyRegistryRendersEmptyRatherThanFailing() {
        assertThat(PrometheusExposition.render(new MetricRegistry())).isEmpty();
    }
}
