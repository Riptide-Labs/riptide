/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.telemetry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Wall time spent on work, as seconds; {@code rate()} of it reads 0 to 1: one worker's own
 * utilization if it alone adds to this counter, or several workers' mean utilization when a
 * caller apportions one interval across them with {@link #add(long)}.
 *
 * <p>Wall time, not the thread's CPU time, on purpose: a flusher waiting on a slow insert is busy
 * even though it burns no CPU. The caller brackets only the unit of work, so time spent waiting for
 * work to arrive is not counted and the rate is utilization rather than uptime.
 */
public final class BusyTime implements SecondsCounter {

    private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1L);

    private final LongAdder nanos = new LongAdder();

    /** Adds the time since {@code startNanos}, a {@link System#nanoTime()} reading. */
    public void addSince(final long startNanos) {
        this.nanos.add(System.nanoTime() - startNanos);
    }

    /** Adds {@code nanos} directly, for a caller that apportions one interval across workers. */
    public void add(final long nanos) {
        this.nanos.add(nanos);
    }

    @Override
    public double seconds() {
        return this.nanos.sum() / NANOS_PER_SECOND;
    }
}
