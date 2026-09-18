/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import java.time.Duration;

/**
 * A watched source that paces itself and reports how its last boot went.
 *
 * <p><b>Why this exists.</b> {@code InventoryFileReloader} needs three things beyond what
 * {@link FileWatchTrigger.Source} offers, and all three come from the composed discovery document.
 * Typed to that concrete class, the reloader's package imported the discovery package while the
 * discovery package already imported this one, so the two depended on each other in both
 * directions (#806). The interface lives here, on this side of the dependency, and discovery
 * implements it.</p>
 *
 * <p><b>Why not {@code FileWatchTrigger.Source} itself.</b> More than one component implements that:
 * the classification ruleset source is an unconditional bean of it. A reloader injected by the wider
 * type could resolve to the ruleset, and the symptom is silent — the inventory watcher polls the
 * wrong document, the inventory is never re-read, and nothing reports an error. This interface is
 * narrower, the ruleset source does not implement it, and so the wrong bean cannot be selected. That
 * is a property of the type rather than of a qualifier name matching, which is why
 * {@code InventoryWatcherInjectionTest} asserts it rather than a comment claiming it.</p>
 *
 * <p><b>What belongs here.</b> What one caller, the inventory watcher, needs to schedule and to
 * decide staleness. Three members, and a fourth needs the same justification these have: a member
 * added for convenience turns this back into the concrete type it exists to replace.</p>
 */
public interface PacedInventorySource extends FileWatchTrigger.Source {

    /**
     * How often the watcher re-reads this source, which for the composed document is
     * {@code riptide.discovery.interval} and never {@code riptide.config.reload-interval}: enabling
     * discovery must not also require enabling config hot-reload.
     */
    Duration interval();

    /**
     * Whether the last boot read served without this source's remote half. The watcher latches its
     * staleness gauge at start when it did, and skips seeding its hashes, which would otherwise
     * record a later successful fetch as already committed and never publish it.
     */
    boolean degradedAtBoot();

    /**
     * Whether the remote half is answering as absent rather than failing. Absence from a remote half
     * latches staleness where a missing local file does not: an operator who deleted a file knows
     * they did, while an endpoint that starts answering 404 under a running collector is invisible
     * without the gauge.
     */
    boolean endpointAbsent();
}
