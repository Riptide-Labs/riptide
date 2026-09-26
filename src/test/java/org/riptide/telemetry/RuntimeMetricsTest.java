/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.telemetry;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.sun.management.UnixOperatingSystemMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeMetricsTest {

    @Test
    void g1RemarkAndCleanupPausesAreCountedDespiteTheConcurrentBeanName() {
        final Map<String, Long> beans = new LinkedHashMap<>();
        beans.put("G1 Young Generation", 1_500L);
        beans.put("G1 Old Generation", 250L);
        // JDK 20+: this bean times G1's Remark and Cleanup pauses, which stop the application
        beans.put("G1 Concurrent GC", 30L);

        assertThat(RuntimeMetrics.pauseSeconds(beans)).isEqualTo(1.78d);
    }

    @Test
    void zgcCyclesAreExcludedAndItsPausesKept() {
        final Map<String, Long> beans = new LinkedHashMap<>();
        beans.put("ZGC Major Cycles", 40_000L);
        beans.put("ZGC Major Pauses", 12L);

        assertThat(RuntimeMetrics.pauseSeconds(beans)).isEqualTo(0.012d);
    }

    @Test
    void anUnknownCollectorIsCountedAndAnUndefinedTimeIsNot() {
        final Map<String, Long> beans = new LinkedHashMap<>();
        // Over-counting an unknown collector beats hiding it; -1 is the bean's "undefined".
        beans.put("Copy", 300L);
        beans.put("MarkSweepCompact", -1L);

        assertThat(RuntimeMetrics.pauseSeconds(beans)).isEqualTo(0.3d);
    }

    @Test
    void fileDescriptorSeriesAreAbsentWithoutAUnixOperatingSystemBean() {
        final var registry = new MetricRegistry();
        final OperatingSystemMXBean plain = mock(OperatingSystemMXBean.class);

        new RuntimeMetrics(ManagementFactory.getMemoryMXBean(), plain,
                ManagementFactory.getThreadMXBean(), List.of()).registerWith(registry);

        assertThat(registry.getNames())
                .doesNotContain("process.openFds", "process.maxFds", "jvm.cpu.processSeconds")
                .contains("jvm.heap.used", "jvm.threads.live", "jvm.gc.seconds");
    }

    @Test
    void anUndefinedHeapMaximumPublishesNoValue() {
        final var registry = new MetricRegistry();
        final MemoryMXBean memory = mock(MemoryMXBean.class);
        when(memory.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0L, 1024L, 2048L, -1L));

        new RuntimeMetrics(memory, ManagementFactory.getOperatingSystemMXBean(),
                ManagementFactory.getThreadMXBean(), List.of()).registerWith(registry);

        final Gauge<?> max = registry.getGauges().get("jvm.heap.max");
        assertThat(max.getValue()).isNull();
        assertThat(registry.getGauges().get("jvm.heap.used").getValue()).isEqualTo(1024L);
    }
    @Test
    void eachSeriesReadsItsOwnBeanInItsOwnUnit() {
        final var registry = new MetricRegistry();
        final MemoryMXBean memory = mock(MemoryMXBean.class);
        when(memory.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0L, 1024L, 2048L, 4096L));
        final UnixOperatingSystemMXBean os = mock(UnixOperatingSystemMXBean.class);
        when(os.getProcessCpuTime()).thenReturn(2_500_000_000L);
        when(os.getOpenFileDescriptorCount()).thenReturn(17L);
        when(os.getMaxFileDescriptorCount()).thenReturn(1024L);
        final ThreadMXBean threads = mock(ThreadMXBean.class);
        when(threads.getThreadCount()).thenReturn(42);
        when(threads.getDaemonThreadCount()).thenReturn(7);
        final GarbageCollectorMXBean young = mock(GarbageCollectorMXBean.class);
        when(young.getName()).thenReturn("G1 Young Generation");
        when(young.getCollectionTime()).thenReturn(500L);

        new RuntimeMetrics(memory, os, threads, List.of(young)).registerWith(registry);

        final var gauges = registry.getGauges();
        assertThat(gauges.get("jvm.heap.used").getValue()).isEqualTo(1024L);
        assertThat(gauges.get("jvm.heap.max").getValue()).isEqualTo(4096L);
        assertThat(gauges.get("jvm.threads.live").getValue()).isEqualTo(42);
        assertThat(gauges.get("jvm.cpu.availableProcessors").getValue())
                .isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(gauges.get("process.openFds").getValue()).isEqualTo(17L);
        assertThat(gauges.get("process.maxFds").getValue()).isEqualTo(1024L);
        // nanoseconds and milliseconds both come out as seconds
        assertThat(seconds(registry, "jvm.cpu.processSeconds")).isEqualTo(2.5d);
        assertThat(seconds(registry, "jvm.gc.seconds")).isEqualTo(0.5d);
    }

    private static double seconds(final MetricRegistry registry, final String name) {
        return ((SecondsCounter) registry.getMetrics().get(name)).seconds();
    }
}
