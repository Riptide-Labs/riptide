/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The exporters a discovery document yielded, keyed by exporter name, plus how many entries were
 * dropped for want of a usable address.
 *
 * <p>Sorted, and that is load-bearing rather than tidy: the Prometheus contract states target lists
 * are unordered, and the content-hash short-circuit in {@code FileWatchTrigger} is the only thing
 * that stops an unstable response ordering from republishing the inventory on every poll.</p>
 *
 * @param byName exporter name to address
 * @param skipped entries with no usable address, reported rather than guessed at
 */
public record RenderedExporters(SortedMap<String, String> byName, int skipped) {

    public RenderedExporters {
        byName = Collections.unmodifiableSortedMap(new TreeMap<>(byName));
    }
}
