/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.telemetry;

import com.codahale.metrics.Metric;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * A monotonic total in seconds, with its fraction: CPU time, GC pause time, a worker's busy time.
 *
 * <p>Its own metric type because a Dropwizard {@code Counter} holds a {@code long}, which would
 * force nanoseconds or milliseconds into every query. The exposition renders it as a Prometheus
 * {@code counter}, so {@code rate()} reads 0 to 1 whether it tracks one thread's own busy seconds
 * or several workers' mean, as {@link BusyTime#add} apportions them.
 */
public interface SecondsCounter extends Metric {

    double seconds();

    /** A counter read from a cumulative source, such as an MXBean total. */
    static SecondsCounter of(final DoubleSupplier seconds) {
        Objects.requireNonNull(seconds);
        return seconds::getAsDouble;
    }
}
