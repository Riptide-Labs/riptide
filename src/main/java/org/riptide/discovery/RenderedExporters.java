/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * The exporters a discovery document yielded, keyed by exporter name, plus how many entries were
 * dropped for want of a usable address.
 *
 * <p>Sorted by name, and that is load-bearing rather than tidy: the Prometheus contract states
 * target lists are unordered, and the content-hash short-circuit in {@code FileWatchTrigger} is the
 * only thing that stops an unstable response ordering from republishing the inventory on every
 * poll.</p>
 *
 * <p><b>This is the one place the ordering is decided.</b> {@code ExporterRenderer} used to sort as
 * well, which meant neither copy was load-bearing: deleting either left the test that proves the
 * same groups render identically in any order still passing (#808).</p>
 *
 * @param byName exporter name to address, natural-ordered whatever the caller handed in
 * @param skipped entries with no usable address, reported rather than guessed at
 */
public record RenderedExporters(Map<String, String> byName, int skipped) {

    public RenderedExporters {
        // Map, not SortedMap, and that is the stronger guarantee rather than the weaker one.
        // TreeMap's copy constructor is overloaded: TreeMap(SortedMap) inherits the source's
        // comparator, so with this component declared SortedMap a caller handing in a
        // reverse-ordered map got a reverse-ordered document — "sorted however the caller chose",
        // not a deterministic order. Declared Map, overload resolution picks TreeMap(Map), which
        // always uses natural ordering, and no caller can choose otherwise.
        byName = Collections.unmodifiableSortedMap(new TreeMap<>(byName));
    }
}
