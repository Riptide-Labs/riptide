/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the published {@link InventorySnapshot}. The loader fails startup before any
 * serving state is published when the inventory file is unreadable or invalid; on
 * success one immutable snapshot instance is published behind this single volatile
 * reference, so a whole-instance swap is the entire concurrency story (AD-3), and a
 * hot reload is exactly such a swap. This bean performs no IO after startup and
 * serves no consumers yet: the enrichers and the poller cut over in story 2.8.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Inventory {

    // volatile, not final: a main-config reload rebinds the credential and polling
    // profiles, and the snapshot's entries hold resolved objects from them (AD-5), so the
    // two have to move together or a rotated credential never reaches a walk
    @NonNull
    private volatile SnmpProfilesConfig profiles;

    @NonNull
    private final InventoryConfig config;

    private volatile InventorySnapshot active = InventorySnapshot.empty();

    @PostConstruct
    public void load() {
        // boot commits through the same path as reload, so the whole-instance swap
        // stays the only way serving state ever changes. Warnings flush after the swap:
        // boot either publishes or dies, and the log must never describe a candidate
        // that did not go live (#539)
        final InventoryLoader.ParseResult result = InventoryLoader.load(this.profiles, this.config.getFile());
        final InventorySnapshot loaded = result.snapshot();
        swap(loaded);
        result.flushWarnings();
        if (this.config.getFile() == null) {
            // silence here would read as "working" while every flow goes unenriched
            log.info("No inventory file configured (riptide.inventory.file): serving the empty inventory");
        } else {
            log.info("Inventory loaded from {}: {} agent ranges, {} enrichment entries",
                    this.config.getFile(), loaded.agentCount(), loaded.exporterCount());
        }
    }

    /**
     * Atomically replaces the serving snapshot. One volatile write of a whole immutable
     * instance is the concurrency story for readers (AD-3): views captured from the
     * previous snapshot keep a consistent pair of trees. Synchronized and guarded like
     * every other publication path — this used to be neither, which made it both the
     * method a future publisher would reach for and the one that bypassed the torn-write
     * guard entirely, and let it interleave unseen inside {@link #rebuildAndSwap}'s
     * monitor-held read-then-write (#535). Boot publishes over the fresh empty, so the
     * guard passes trivially there; test publishers that re-swap must keep their
     * sequences non-regressive or declare the empty tree, same as an operator.
     *
     * @throws IllegalStateException on a publish that would drop an entire tree
     */
    public synchronized void swap(final InventorySnapshot snapshot) {
        // throwing is right here: the callers are boot (guard trivially passes over the
        // fresh empty) and test publishers, where a regressive publish is a broken fixture
        // that deserves a loud failure, not a deferral nothing retries
        requireNotRegressive(snapshot);
        this.active = Objects.requireNonNull(snapshot);
    }

    /**
     * Publishes a snapshot only if the profiles it was parsed against are still the ones
     * serving, and reports whether it did.
     *
     * <p>Two reloaders publish here on separate threads. The inventory watcher reads the
     * profiles, parses a file against them, and commits, and a main-config reload rotating
     * a credential can land in between: committing anyway would pair the new profiles with
     * a snapshot built from the old ones and silently undo the rotation. Losing the race
     * means re-reading and re-parsing, which the caller does on its next cycle.</p>
     */
    @SuppressWarnings("ReferenceEquality")
    public synchronized boolean swapIfProfilesUnchanged(final SnmpProfilesConfig parsedWith,
                                                        final InventorySnapshot snapshot) {
        // identity on purpose: the question is whether the object this caller parsed against is
        // still the one installed, which is what a compare-and-swap guard asks. SnmpProfilesConfig
        // is a record, so .equals is a real alternative rather than the same test spelled
        // differently — and a looser one. It would deep-compare both credential maps on every
        // commit, and would accept a config that was genuinely swapped for a structurally
        // identical one. That acceptance is harmless, the cost is not, and losing the race only
        // means re-reading on the next cycle.
        if (this.profiles != parsedWith) {
            return false;
        }
        // the caller pre-checks against its own read of the serving snapshot and logs the
        // operator-facing refusal; this monitor-held check closes the window between that
        // read and this commit. Deferral, not a throw: reaching it means the serving
        // snapshot changed mid-parse, which is exactly the condition the false return
        // already means — the caller resets its attempted hash and re-parses next cycle
        // against what is serving by then. A throw here landed in the caller's failure
        // path and left the attempted hash committed, wedging retries until the file
        // changed again
        if (snapshot.isRegressiveOver(this.active)) {
            return false;
        }
        this.active = Objects.requireNonNull(snapshot);
        return true;
    }

    /**
     * Rebuilds from {@code file} against {@code profiles} and publishes both, atomically
     * with respect to {@link #swapIfProfilesUnchanged}.
     *
     * <p>The read happens inside the monitor deliberately. A main-config reload rotating a
     * credential has to load the inventory file to resolve it, and the inventory watcher
     * can commit newer file content during that load: publishing afterwards would
     * overwrite it with older content, and neither reloader would notice, because both
     * would consider their own hashes committed. Holding the monitor makes the watcher's
     * compare-and-set fail instead, which it handles by re-parsing on its next cycle.</p>
     */
    public synchronized InventorySnapshot rebuildAndSwap(final SnmpProfilesConfig profiles, final Path file) {
        final InventoryLoader.ParseResult result = InventoryLoader.load(profiles, file);
        final InventorySnapshot rebuilt = result.snapshot();
        if (rebuilt.isRegressiveOver(this.active)) {
            // refused, not published: a file caught mid-write parses cleanly with one tree
            // missing, and publishing that would either deregister the whole polled fleet
            // or drop every exporter name at once, depending on which tree the writer
            // flushed first. The check lives here, inside the monitor and against the same
            // read that would be published, so nothing can change between deciding and
            // committing. Null keeps the existing contract: ConfigFileReloader parks the
            // profiles as pending and retries each poll until the full write lands
            return null;
        }
        this.profiles = Objects.requireNonNull(profiles);
        this.active = rebuilt;
        // flush only past the guard: a refused candidate's warnings would read as
        // though the warned-about state went live when nothing changed (#539)
        result.flushWarnings();
        return rebuilt;
    }

    /** The guard for the throwing publication paths; names the loss the way an operator reads it. */
    private void requireNotRegressive(final InventorySnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (snapshot.isRegressiveOver(this.active)) {
            throw new IllegalStateException(
                    ("Refusing to publish an inventory that drops a whole tree: %d -> %d agent "
                            + "range(s), %d -> %d enrichment entrie(s). A partially written file "
                            + "reads this way; write the inventory atomically (write then mv). To "
                            + "deliberately empty a tree, write it as an explicit empty mapping "
                            + "(agents: {} / exporters: {}); to stop polling a fleet while keeping "
                            + "its entries, set enabled: false on a covering range.")
                            .formatted(this.active.agentCount(), snapshot.agentCount(),
                                    this.active.exporterCount(), snapshot.exporterCount()));
        }
    }

    /** The profiles the serving snapshot was built from, for a reloader re-parsing the file. */
    public SnmpProfilesConfig profiles() {
        return this.profiles;
    }

    /** The current snapshot: exactly one volatile read; capture views from it, not from here. */
    public InventorySnapshot snapshot() {
        return this.active;
    }
}
