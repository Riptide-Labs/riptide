/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.snmp.InterfaceSnapshotPoller;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Hot-reload of the dedicated inventory file ({@code riptide.inventory.file}):
 * agent-range and exporter changes apply without a restart. A sibling of
 * {@link ConfigFileReloader}, sharing its trigger by owning a second
 * {@link FileWatchTrigger} rather than by being the same bean: the main reloader's
 * commit substitutes property sources, which never applies here: the inventory file is
 * direct-parsed by design and is never a property source. The watcher only watches and
 * triggers (AD-4); {@link InventoryLoader} parses and validates, {@link Inventory} serves.
 *
 * <p><b>Trigger</b>: {@link FileWatchTrigger}'s mtime-independent content-hash poll,
 * the one copy of the loop the main reloader also runs: path re-resolved every cycle,
 * missing file skips (atomic
 * {@code rm}+{@code mv} replacement is indistinguishable from deletion), empty or
 * blank file skips (a shell {@code >} redirect truncates before writing), unchanged
 * or already-attempted content short-circuits. The skips defuse truncate-style
 * rewrite races only: a non-atomic in-place writer can still expose a parseable
 * prefix for one interval, which the next cycle heals. Atomic rename replacement
 * remains the recommended write pattern.</p>
 *
 * <p><b>Failure semantics</b>: the candidate runs through the same loader as boot;
 * a failing reload keeps the last good snapshot serving, warns with the loader's
 * entry-naming message, and counts the failure with a staleness gauge.</p>
 *
 * <p><b>Where profiles come from</b>: the candidate is parsed against the profiles the
 * {@link Inventory} currently holds, not a copy captured at boot, because a main-config
 * reload republishes both the profiles and an inventory rebuilt from them. That is what
 * lets a credential rotation reach a range edited afterwards. The location is still
 * captured at start, so changing {@code riptide.inventory.file} needs a restart before
 * this watcher follows it.</p>
 */
@Slf4j
@Component
public class InventoryFileReloader {

    private final ConfigReloadProperties properties;
    private final InventoryConfig inventoryConfig;
    private final Inventory inventory;
    private final InterfaceSnapshotPoller interfacePoller;

    private final MetricRegistry metrics;
    private final Counter reloadSuccesses;
    private final Counter reloadFailures;

    /** The shared poll loop: schedule, hashes, skips, failure counting, gauges. */
    private FileWatchTrigger trigger;
    private Path location;

    public InventoryFileReloader(final ConfigReloadProperties properties,
                                 final InventoryConfig inventoryConfig,
                                 final Inventory inventory,
                                 final InterfaceSnapshotPoller interfacePoller,
                                 final MetricRegistry metrics) {
        this.properties = Objects.requireNonNull(properties);
        this.inventoryConfig = Objects.requireNonNull(inventoryConfig);
        this.inventory = Objects.requireNonNull(inventory);
        this.interfacePoller = Objects.requireNonNull(interfacePoller);

        // counters stay here: a zero counter is true when reloading is disabled. The
        // gauges register from start(), after the disabled early-returns (#539): a gauge
        // registered here published a constant 0 with reloading disabled, which reads as
        // "the file matches what is serving" for a file that is never read again
        this.metrics = metrics;
        this.reloadSuccesses = metrics.counter(MetricRegistry.name("inventory", "reload", "successes"));
        this.reloadFailures = metrics.counter(MetricRegistry.name("inventory", "reload", "failures"));
    }

    @PostConstruct
    void start() {
        if (this.properties.getReloadInterval() == null || this.properties.getReloadInterval().isZero()
                || this.properties.getReloadInterval().isNegative()) {
            log.debug("Inventory hot-reload disabled (no riptide.config.reload-interval)");
            return;
        }
        this.location = this.inventoryConfig.getFile();
        if (this.location == null) {
            log.debug("Inventory hot-reload disabled (no riptide.inventory.file)");
            return;
        }
        // hashes seeded from the content boot just served, so the first cycle does not
        // spuriously recommit an unchanged file (the boot file is guaranteed present here,
        // since a set-but-missing file fails startup)
        this.trigger = new FileWatchTrigger(log, this.location, this.properties.getReloadInterval(),
                "InventoryFileReloader", messages(this.location), this.metrics, "inventory",
                this.reloadFailures, true, new FileWatchTrigger.Cycle() {
                    @Override
                    public void onContent(final byte[] content) throws Exception {
                        reload(content);
                    }

                    @Override
                    public void onIdle() {
                        // nothing is ever left pending here: the inventory commit either
                        // publishes, defers to the next cycle, or is refused outright
                    }

                    @Override
                    public void onFailure(final Exception e) {
                        // one WARN, and since #630 the loader's message is a multi-line
                        // report listing every bad entry: it renders as a multi-line WARN
                        // here, deliberately. The alternative — one line per problem —
                        // interleaves with other threads' logging and stops being one
                        // readable failure, which is the whole point of collecting them
                        log.warn("Inventory reload failed, keeping the last good inventory: {}", e.getMessage(), e);
                    }
                });
        this.trigger.start(() -> this.trigger.isStale() ? 1 : 0);
        log.info("Inventory hot-reload enabled: watching {} every {}", this.location, this.properties.getReloadInterval());
    }

    /** The skip and shutdown sentences, spelled the way this reloader has always spelled them. */
    private static FileWatchTrigger.Messages messages(final Path location) {
        return new FileWatchTrigger.Messages(
                "Inventory reload poll skipped: thread interrupted (shutdown)",
                "Inventory reload poll interrupted mid-cycle (shutdown): {}",
                ("Inventory file %s is missing: skipping reload cycles until it reappears "
                        + "(deletion and atomic replacement are indistinguishable; keeping the running inventory)")
                        .formatted(location),
                // "empty or whitespace-only", not "empty": the skip has always covered
                // whitespace here, and an operator told "is empty" about an 8-byte file
                // goes looking for a second problem that does not exist
                ("Inventory file %s is empty or whitespace-only: skipping reload cycle "
                        + "(truncate-write race or intentional; keeping the running inventory)").formatted(location),
                "Inventory reload housekeeping failed unexpectedly; the reload schedule keeps running: {}");
    }

    @PreDestroy
    void stop() {
        if (this.trigger != null) {
            this.trigger.stop();
        }
    }

    // visible for the scheduled task and tests; never throws (a throwing scheduled
    // task would silently cancel the schedule). The loop itself lives in the trigger,
    // which calls back into reload()
    void poll() {
        if (this.trigger != null) {
            this.trigger.poll();
        }
    }

    /** The commit path: parse the candidate, refuse or defer it, or publish it. */
    private void reload(final byte[] content) throws Exception {
        // the exact pure function boot uses: parse + validate + resolve (AD-4);
        // throws with entry-and-file-naming messages -> keep-old in the trigger's catch.
        // The decode is strict like boot's Files.readString: malformed bytes must
        // fail the reload here, not the next restart
        // the profiles as they are now, not as they were at boot: a main-config reload
        // can have rotated a credential since
        // captured, then republished with the candidate: parsing against one set of
        // profiles and committing while another is live would pair a snapshot with
        // profiles it was not built from, and the config reloader can commit between
        // these two lines
        final SnmpProfilesConfig parsedWith = this.inventory.profiles();
        // parseWithWarnings, not parse: the walk's warnings describe the candidate
        // as if it were live, so they flush only after the swap below commits — a
        // refused or deferred candidate logs nothing from the walk (#539)
        final InventoryLoader.ParseResult parsed = InventoryLoader.parseWithWarnings(parsedWith,
                strictUtf8(content, this.location), this.location.toString());
        final InventorySnapshot candidate = parsed.snapshot();

        final InventorySnapshot serving = this.inventory.snapshot();
        if (candidate.isRegressiveOver(serving)) {
            // per tree, not whole-file: a non-atomic writer flushes the trees in file
            // order, so a mid-write read has one tree populated and one empty, and
            // publishing it would deregister the whole polled fleet or drop every
            // exporter name. Deleting the file already keeps the old inventory
            // serving, so refusing this is the same rule, not a new one. This is a
            // pre-check for the message; the monitor-held guard in Inventory decides
            // pre-formatted, not SLF4J placeholders: the message teaches the literal
            // "agents: {}" idiom, and {} in an SLF4J format string IS a placeholder —
            // the first version consumed its own arguments and printed shifted counts
            log.warn(("Inventory file %s would drop a whole tree (%d -> %d agent range(s), %d -> %d "
                    + "enrichment entry/entries): keeping the running inventory (a partially written "
                    + "file reads this way; write atomically via mv). To deliberately empty a "
                    + "tree, write it as an explicit empty mapping (agents: {} / exporters: {}); "
                    + "to stop polling while keeping entries, set enabled: false on a covering "
                    + "range").formatted(this.location,
                    serving.agentCount(), candidate.agentCount(),
                    serving.exporterCount(), candidate.exporterCount()));
            // latch immediately, like the failure path: the file on disk does not match
            // what is serving. Without this the gauge read 0 until the next cycle's
            // unchanged-content recompute flipped it — a one-interval blink the docs'
            // "a rejected file raises inventory.reload.stale" never had
            this.trigger.setStale(true);
            return;
        }

        if (!this.inventory.swapIfProfilesUnchanged(parsedWith, candidate)) {
            // a main-config reload republished the profiles while this candidate was
            // being parsed; committing would undo it. Leave the attempted hash unset so
            // the next cycle re-parses against what is now serving
            this.trigger.rollbackAttempt();
            log.info("Inventory reload deferred: the credential and polling profiles changed while {} "
                    + "was being parsed, so it is re-read on the next cycle", this.location);
            return;
        }
        // Marked here, immediately after the swap above — #718's rule is "mark once what is
        // serving has changed", and swapIfProfilesUnchanged is that point. Everything below is
        // bookkeeping over a snapshot that is already live: flushWarnings() only logs. Moving the
        // mark past it was tried and reverted, because a throw from logging would then leave the
        // inventory serving and matching the file while stale latched at 1 with no self-heal until
        // the content changed — a permanent false alarm, worse than the transient one.
        this.trigger.markCommitted();
        this.reloadSuccesses.inc();
        this.trigger.setStale(false);
        parsed.flushWarnings();

        // swap, then refresh (AD-6): registrations built from the previous inventory
        // are re-resolved against this one, so a carve-out reaches an agent that is
        // already being polled instead of waiting out its deregistration deadline.
        // Guarded separately and after the commit bookkeeping: the snapshot IS serving
        // by now, so a failure here must not report the reload as failed, latch
        // staleness against content that is actually live, and then never retry
        // because the hash already matches
        try {
            this.interfacePoller.refreshRegistrations();
        } catch (final Exception e) {
            log.warn("Inventory reloaded, but refreshing polled endpoints failed: registrations keep "
                    + "their previous endpoints until their next flow or deregistration", e);
        }
        log.info("Inventory reloaded from {}: {} agent ranges, {} enrichment entries",
                this.location, candidate.agentCount(), candidate.exporterCount());
    }

    private static String strictUtf8(final byte[] content, final Path location) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (final CharacterCodingException e) {
            // named like every loader error: two reloaders share this log
            throw new IllegalStateException(
                    "Inventory file %s is not valid UTF-8 (%s)".formatted(location, e), e);
        }
    }
}
