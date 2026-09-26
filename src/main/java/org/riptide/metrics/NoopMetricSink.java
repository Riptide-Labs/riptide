/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import java.util.List;

/**
 * The sink when {@code riptide.metrics.remote-write.url} is unset: samples are discarded silently.
 */
public final class NoopMetricSink implements MetricSink {

    @Override
    public void accept(final List<Sample> samples) {
        // nothing to do
    }
}
