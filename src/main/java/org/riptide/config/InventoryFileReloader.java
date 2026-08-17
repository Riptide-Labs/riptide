/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hot-reload of the dedicated inventory file ({@code riptide.inventory.file}):
 * agent-range and exporter changes apply without a restart. A sibling of
 * {@link ConfigFileReloader} sharing its trigger discipline, deliberately not the
 * same bean: the main reloader's commit substitutes property sources, which never
 * applies here: the inventory file is direct-parsed by design and is never a
 * property source. The watcher only watches and triggers (AD-4);
 * {@link InventoryLoader} parses and validates, {@link Inventory} serves.
 *
 * <p><b>Trigger</b>: the same mtime-independent content-hash poll as the main
 * reloader: path re-resolved every cycle, missing file skips (atomic
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

    private final Counter reloadSuccesses;
    private final Counter reloadFailures;
    private volatile boolean stale = false;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> polling;
    private Path location;
    private byte[] lastAttemptedHash = new byte[0];
    private byte[] lastCommittedHash = new byte[0];
    private boolean warnedMissing = false;
    // the empty-file condition persists just like the missing-file one, so it gets the
    // same latch: a truncated file at a 5s interval would otherwise warn forever
    private boolean warnedEmpty = false;

    public InventoryFileReloader(final ConfigReloadProperties properties,
                                 final InventoryConfig inventoryConfig,
                                 final Inventory inventory,
                                 final InterfaceSnapshotPoller interfacePoller,
                                 final MetricRegistry metrics) {
        this.properties = Objects.requireNonNull(properties);
        this.inventoryConfig = Objects.requireNonNull(inventoryConfig);
        this.inventory = Objects.requireNonNull(inventory);
        this.interfacePoller = Objects.requireNonNull(interfacePoller);

        this.reloadSuccesses = metrics.counter(MetricRegistry.name("inventory", "reload", "successes"));
        this.reloadFailures = metrics.counter(MetricRegistry.name("inventory", "reload", "failures"));
        metrics.register(MetricRegistry.name("inventory", "reload", "stale"), (Gauge<Integer>) () -> this.stale ? 1 : 0);
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
        seedHashesFromBootContent();
        final long millis = this.properties.getReloadInterval().toMillis();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "InventoryFileReloader"));
        // The handle is kept and cancelled explicitly rather than discarded. poll() swallows every
        // Exception itself, so a bad reload cycle cannot silently cancel the schedule and leave
        // hot-reload dead for the process lifetime (which is the failure this return value exists
        // to make visible).
        this.polling = this.executor.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Inventory hot-reload enabled: watching {} every {}", this.location, this.properties.getReloadInterval());
    }

    /**
     * Seeds both hashes from the content boot just served, so the first cycle does
     * not spuriously recommit an unchanged file (the boot file is guaranteed present
     * here, since a set-but-missing file fails startup). Best-effort: a read failure
     * leaves the hashes empty and the first poll re-parses, which is safe. An edit
     * racing this read (between boot's load and here) would be missed until the
     * content changes again, a sub-second window at startup, accepted.
     */
    private void seedHashesFromBootContent() {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(this.location));
            this.lastAttemptedHash = hash;
            this.lastCommittedHash = hash;
        } catch (final Exception e) {
            log.debug("Could not seed reload hashes from {}: {}", this.location, e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (this.polling != null) {
            this.polling.cancel(true);
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    // visible for the scheduled task and tests; never throws (a throwing scheduled
    // task would silently cancel the schedule)
    void poll() {
        try {
            if (!Files.isRegularFile(this.location)) {
                if (!this.warnedMissing) {
                    log.warn("Inventory file {} is missing: skipping reload cycles until it reappears "
                            + "(deletion and atomic replacement are indistinguishable; keeping the running inventory)", this.location);
                    this.warnedMissing = true;
                }
                return;
            }
            this.warnedMissing = false;

            final byte[] content;
            try {
                content = Files.readAllBytes(this.location);
            } catch (final NoSuchFileException e) {
                // the file vanished between the check and the read: an atomic rm+mv
                // replacement or a symlink swap, which is the healthy deploy this class
                // expects, not a failure worth counting
                return;
            }
            if (isBlank(content)) {
                // a shell '>' redirect truncates before writing, and editors flush
                // whitespace-only intermediate states: indistinguishable from an
                // intentionally emptied file; never commit on empty or blank
                if (!this.warnedEmpty) {
                    log.warn("Inventory file {} is empty: skipping reload cycle (truncate-write race or intentional; keeping the running inventory)", this.location);
                    this.warnedEmpty = true;
                }
                return;
            }
            this.warnedEmpty = false;
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            if (MessageDigest.isEqual(hash, this.lastAttemptedHash)) {
                // unchanged, or the same bad content we already warned about; staleness
                // reflects whether the file matches what is running (a transient read
                // failure must not latch the gauge)
                this.stale = !MessageDigest.isEqual(hash, this.lastCommittedHash);
                return;
            }
            this.lastAttemptedHash = hash;

            // the exact pure function boot uses: parse + validate + resolve (AD-4);
            // throws with entry-and-file-naming messages -> keep-old below. The
            // decode is strict like boot's Files.readString: malformed bytes must
            // fail the reload here, not the next restart
            // the profiles as they are now, not as they were at boot: a main-config reload
            // can have rotated a credential since
            // captured, then republished with the candidate: parsing against one set of
            // profiles and committing while another is live would pair a snapshot with
            // profiles it was not built from, and the config reloader can commit between
            // these two lines
            final SnmpProfilesConfig parsedWith = this.inventory.profiles();
            final InventorySnapshot candidate = InventoryLoader.parse(parsedWith,
                    strictUtf8(content, this.location), this.location.toString());

            final InventorySnapshot serving = this.inventory.snapshot();
            if (candidate.isRegressiveOver(serving)) {
                // per tree, not whole-file: a non-atomic writer flushes the trees in file
                // order, so a mid-write read has one tree populated and one empty, and
                // publishing it would deregister the whole polled fleet or drop every
                // exporter name. Deleting the file already keeps the old inventory
                // serving, so refusing this is the same rule, not a new one. This is a
                // pre-check for the message; the monitor-held guard in Inventory decides
                log.warn("Inventory file {} would drop a whole tree ({} -> {} agent range(s), {} -> {} "
                        + "enrichment entrie(s)): keeping the running inventory (a partially written "
                        + "file reads this way; write atomically via mv). To deliberately empty a "
                        + "tree, write it as an explicit empty mapping (agents: {} / exporters: {}); "
                        + "to stop polling while keeping entries, set enabled: false on a covering "
                        + "range", this.location,
                        serving.agentCount(), candidate.agentCount(),
                        serving.exporterCount(), candidate.exporterCount());
                return;
            }

            if (!this.inventory.swapIfProfilesUnchanged(parsedWith, candidate)) {
                // a main-config reload republished the profiles while this candidate was
                // being parsed; committing would undo it. Leave the attempted hash unset so
                // the next cycle re-parses against what is now serving
                this.lastAttemptedHash = this.lastCommittedHash;
                log.info("Inventory reload deferred: the credential and polling profiles changed while {} "
                        + "was being parsed, so it is re-read on the next cycle", this.location);
                return;
            }
            this.lastCommittedHash = this.lastAttemptedHash;
            this.reloadSuccesses.inc();
            this.stale = false;

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
        } catch (final Exception e) {
            this.reloadFailures.inc();
            this.stale = true;
            log.warn("Inventory reload failed, keeping the last good inventory: {}", e.getMessage(), e);
        }
    }

    /** Whitespace is ASCII-safe in UTF-8, so blankness is decidable on raw bytes. */
    private static boolean isBlank(final byte[] content) {
        for (final byte b : content) {
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                return false;
            }
        }
        return true;
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
