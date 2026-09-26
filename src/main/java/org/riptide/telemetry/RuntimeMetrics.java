/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.telemetry;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.sun.management.UnixOperatingSystemMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The JVM and process series: CPU, heap, GC pauses, threads and file descriptors.
 *
 * <p>Registered once with the registry and never removed: they describe the process, not a
 * component that starts and stops. A resource the platform cannot measure publishes nothing rather
 * than zero, the rule {@code listeners.<name>.socketDrops} already follows.
 */
public final class RuntimeMetrics {

    private static final double MILLIS_PER_SECOND = TimeUnit.SECONDS.toMillis(1L);
    private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1L);

    private final MemoryMXBean memory;
    private final OperatingSystemMXBean os;
    private final ThreadMXBean threads;
    private final List<GarbageCollectorMXBean> collectors;

    RuntimeMetrics(final MemoryMXBean memory,
                   final OperatingSystemMXBean os,
                   final ThreadMXBean threads,
                   final List<GarbageCollectorMXBean> collectors) {
        this.memory = Objects.requireNonNull(memory);
        this.os = Objects.requireNonNull(os);
        this.threads = Objects.requireNonNull(threads);
        this.collectors = List.copyOf(collectors);
    }

    /** Register the series for this JVM's own platform beans. */
    public static void register(final MetricRegistry registry) {
        new RuntimeMetrics(ManagementFactory.getMemoryMXBean(),
                ManagementFactory.getOperatingSystemMXBean(),
                ManagementFactory.getThreadMXBean(),
                ManagementFactory.getGarbageCollectorMXBeans()).registerWith(registry);
    }

    void registerWith(final MetricRegistry registry) {
        // Runtime.availableProcessors honours a container's cgroup CPU limit, which is the
        // denominator an operator means by "CPU utilization".
        registry.register("jvm.cpu.availableProcessors",
                (Gauge<Integer>) () -> Runtime.getRuntime().availableProcessors());
        registry.register("jvm.heap.used", (Gauge<Long>) () -> this.memory.getHeapMemoryUsage().getUsed());
        registry.register("jvm.heap.max", (Gauge<Long>) () -> {
            // -1 is the bean's "undefined"; null is skipped by the exposition, so no value is published
            final long max = this.memory.getHeapMemoryUsage().getMax();
            return max >= 0 ? max : null;
        });
        registry.register("jvm.gc.seconds", SecondsCounter.of(() -> pauseSeconds(collectionMillis())));
        registry.register("jvm.threads.live", (Gauge<Integer>) this.threads::getThreadCount);

        if (this.os instanceof com.sun.management.OperatingSystemMXBean sun && sun.getProcessCpuTime() >= 0) {
            registry.register("jvm.cpu.processSeconds",
                    SecondsCounter.of(() -> sun.getProcessCpuTime() / NANOS_PER_SECOND));
        }
        if (this.os instanceof UnixOperatingSystemMXBean unix) {
            registry.register("process.openFds", (Gauge<Long>) unix::getOpenFileDescriptorCount);
            registry.register("process.maxFds", (Gauge<Long>) unix::getMaxFileDescriptorCount);
        }
    }

    private Map<String, Long> collectionMillis() {
        final Map<String, Long> millis = new LinkedHashMap<>();
        for (final GarbageCollectorMXBean bean : this.collectors) {
            millis.put(bean.getName(), bean.getCollectionTime());
        }
        return millis;
    }

    /**
     * Stop-the-world time, in seconds, summed over the collector beans.
     *
     * <p>Beans named {@code ...Concurrent...} (G1's concurrent cycle) or {@code ...Cycles} (ZGC and
     * Shenandoah whole cycles) measure work that runs beside the application, not time it was
     * stopped, and are left out. Any other bean is counted: over-counting an unknown collector beats
     * hiding its pauses. A negative time is the bean's "undefined" and is skipped.
     */
    static double pauseSeconds(final Map<String, Long> collectionMillisByName) {
        long total = 0;
        for (final Map.Entry<String, Long> bean : collectionMillisByName.entrySet()) {
            final String name = bean.getKey();
            if (name.contains("Concurrent") || name.contains("Cycles") || bean.getValue() < 0) {
                continue;
            }
            total += bean.getValue();
        }
        return total / MILLIS_PER_SECOND;
    }
}
