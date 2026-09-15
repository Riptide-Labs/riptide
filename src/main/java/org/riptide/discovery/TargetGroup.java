/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.util.List;
import java.util.Map;

/**
 * One element of a Prometheus HTTP service discovery document: a set of targets sharing a label set.
 *
 * <p>The NetBox service discovery plugin always emits exactly one target per group. Generic
 * producers emit several, which is why each target becomes its own exporter entry downstream: they
 * share a label set and would otherwise collide on name.</p>
 */
public record TargetGroup(List<String> targets, Map<String, String> labels) {

    public TargetGroup {
        targets = List.copyOf(targets);
        labels = Map.copyOf(labels);
    }
}
