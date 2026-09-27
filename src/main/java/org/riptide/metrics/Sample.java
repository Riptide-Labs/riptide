/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import java.util.Map;

/**
 * One time series sample bound for a remote-write sink. {@code labels} excludes the metric name;
 * the encoder adds {@code __name__}. Labels are copied so a sample is immutable once built.
 */
public record Sample(String name, Map<String, String> labels, double value, long timestampMs) {

    public Sample {
        labels = Map.copyOf(labels);
    }
}
