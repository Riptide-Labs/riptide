/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import java.util.List;

/**
 * Receives samples from the poller. Implementations must not block the caller.
 */
public interface MetricSink {

    void accept(List<Sample> samples);

    /**
     * Called once at shutdown. {@link NoopMetricSink} owns no queue, thread or connection to
     * release, so it needs no override. The default lets it still satisfy the {@code
     * destroyMethod = "stop"} every {@code MetricSink} bean declares.
     */
    default void stop() {
    }
}
