/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.codahale.metrics.MetricRegistry;
import com.sun.management.UnixOperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.riptide.telemetry.RuntimeMetrics;

import java.lang.management.ManagementFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The JVM and process series, read through the rendered text an operator's scrape sees. */
class RuntimeMetricsExpositionTest {

    @Test
    void allEightSeriesRenderWithTheirTypes() {
        // File descriptor counts need the Unix bean; Linux and macOS both provide it.
        assumeTrue(ManagementFactory.getOperatingSystemMXBean() instanceof UnixOperatingSystemMXBean);
        final var registry = new MetricRegistry();
        RuntimeMetrics.register(registry);

        assertThat(PrometheusExposition.render(registry))
                .contains("# TYPE jvm_cpu_processSeconds counter\njvm_cpu_processSeconds ")
                .contains("# TYPE jvm_cpu_availableProcessors gauge\njvm_cpu_availableProcessors ")
                .contains("# TYPE jvm_heap_used gauge\njvm_heap_used ")
                .contains("# TYPE jvm_heap_max gauge\njvm_heap_max ")
                .contains("# TYPE jvm_gc_seconds counter\njvm_gc_seconds ")
                .contains("# TYPE jvm_threads_live gauge\njvm_threads_live ")
                .contains("# TYPE process_openFds gauge\nprocess_openFds ")
                .contains("# TYPE process_maxFds gauge\nprocess_maxFds ");
    }

    @Test
    void processCpuSecondsGrowWithWork() {
        final var registry = new MetricRegistry();
        RuntimeMetrics.register(registry);

        final long wallStart = System.nanoTime();
        final double before = sample(PrometheusExposition.render(registry), "jvm_cpu_processSeconds");
        final long burnNanos = 200_000_000L;
        final long threadCpuStart = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        long sink = 0;
        while (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - threadCpuStart < burnNanos) {
            sink += System.nanoTime() % 7;
        }
        final double after = sample(PrometheusExposition.render(registry), "jvm_cpu_processSeconds");
        final double wallSeconds = (System.nanoTime() - wallStart) / 1e9d;

        assertThat(sink).isNotNegative();
        // this thread alone burned 0.2 s of CPU, so the process total rose by at least that
        assertThat(after - before).isGreaterThanOrEqualTo(0.2d);
        // and by no more than every core busy for the whole window: a unit slip (ms read as ns)
        // would pass the lower bound alone
        assertThat(after - before)
                .isLessThanOrEqualTo(wallSeconds * Runtime.getRuntime().availableProcessors() + 0.1d);
    }

    private static double sample(final String rendered, final String name) {
        final Matcher m = Pattern.compile("(?m)^" + name + " (\\S+)$").matcher(rendered);
        assertThat(m.find()).as("sample line for %s", name).isTrue();
        return Double.parseDouble(m.group(1));
    }
}
