/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.slf4j.Logger;

import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The one copy of the reload trigger both file reloaders own: the schedule, the
 * content hashes, the missing/blank skips and their latches, the unchanged
 * short-circuit, the failure counter, the staleness and dead-schedule gauges, and
 * both interrupt disciplines. Composition, never inheritance — the owner keeps its
 * commit path, its policy and every sentence it says.
 *
 * <p><b>Why it exists</b>: {@link ConfigFileReloader} and {@link InventoryFileReloader}
 * carried byte-identical copies of this loop, and the two copies drifted: a
 * whitespace-only file was a benign skip for one and a counted failure for the other,
 * and one warned about a truncated file on every poll forever because it had no
 * {@code warnedEmpty} latch. A shared trigger cannot hold both behaviours (#561).</p>
 *
 * <p><b>What stays with the owner</b>: the trigger takes the owner's {@link Logger}, so
 * every message it emits keeps the category the owner's tests capture. Messages whose
 * text differs between the two owners are pre-formatted by the owner and handed over in
 * {@link Messages}; the failure sentence is written by the owner in
 * {@link Cycle#onFailure}, because the trigger only counts the failure and latches
 * staleness. The stale gauge's value is supplied to {@link #start(Gauge)} for the same
 * reason: the config reloader ORs a partial-reload state the trigger knows nothing
 * about.</p>
 *
 * <p><b>Trigger</b>: an mtime-independent content-hash poll — the path is re-resolved
 * every cycle, so docker bind mounts and Kubernetes ConfigMap symlink swaps are seen
 * where a {@code WatchService} would miss them.</p>
 *
 * <p><b>Still file-shaped</b>: what the extraction bought #655 is that the change
 * detection, the skip latches, the counters and the interrupt discipline are now
 * written once and are source-agnostic. The source itself is not abstracted: the
 * {@code location} field, the {@code isRegularFile} existence check, the
 * {@code NoSuchFileException} vanish branch and both pre-formatted skip sentences all
 * name a file. A non-file source needs more than substituting the read, and building
 * that seam before there is a consumer for it is out of scope here.</p>
 */
final class FileWatchTrigger {

    /**
     * What the owner does with a cycle the trigger has already classified.
     *
     * <p>{@code onIdle} is invoked from exactly the branches that read nothing new —
     * missing file, vanished-between-check-and-read, blank file, unchanged content, and
     * a counted failure — and from neither interrupt branch. That asymmetry is the
     * contract that keeps the config reloader's pending-rebuild retry healing while the
     * watched file is missing, truncated or churning invalid. Four of the five are pinned
     * by {@code FileWatchTriggerTest}; the vanish branch is the exception, because the
     * file has to disappear between the existence check and the read, which no test can
     * schedule — that one is verified by inspection.</p>
     */
    interface Cycle {
        /** The commit path: parse, validate, publish. A throw is counted as a failure. */
        void onContent(byte[] content) throws Exception;

        /**
         * Owner work that must still happen on a cycle that committed nothing.
         *
         * <p>A throw here is not a reload failure and is never counted as one: this cycle
         * read nothing, and a counter that moves for a cycle that read nothing is exactly
         * what #539 took out of the interrupt path. The trigger catches it, says so
         * through the owner's {@code idleFailure} sentence, and carries on — it must not
         * escape {@code poll()}, because a throwing scheduled task silently cancels the
         * schedule and kills hot-reload for the process lifetime.</p>
         */
        void onIdle();

        /**
         * The owner's own failure sentence; the trigger has already counted and latched.
         * It is a logging call by contract, and must not throw.
         */
        void onFailure(Exception e);
    }

    /**
     * The sentences the two owners spell differently, pre-formatted with the watched
     * location so the trigger never composes owner-facing text.
     *
     * @param interruptedBeforePoll DEBUG for a cycle that begins interrupted
     * @param interruptedMidCycle DEBUG for an interrupt delivered mid-cycle; one
     *     {@code {}} placeholder, filled with the exception's message
     * @param missingFile WARN emitted once while the file is missing
     * @param blankFile WARN emitted once while the file is empty or whitespace-only
     * @param idleFailure WARN for a throwing {@link Cycle#onIdle}; one {@code {}}
     *     placeholder, filled with the exception's message
     */
    record Messages(String interruptedBeforePoll,
                    String interruptedMidCycle,
                    String missingFile,
                    String blankFile,
                    String idleFailure) {

        // every other collaborator is null-checked at construction; a null here would
        // instead surface as an NPE at log time, on the scheduler thread, inside a catch
        // or mid-shutdown — the three places least likely to be read
        Messages {
            Objects.requireNonNull(interruptedBeforePoll, "interruptedBeforePoll");
            Objects.requireNonNull(interruptedMidCycle, "interruptedMidCycle");
            Objects.requireNonNull(missingFile, "missingFile");
            Objects.requireNonNull(blankFile, "blankFile");
            Objects.requireNonNull(idleFailure, "idleFailure");
        }
    }

    private final Logger log;
    private final Path location;
    private final Duration interval;
    private final String threadName;
    private final Messages messages;
    private final MetricRegistry metrics;
    private final String metricPrefix;
    private final Counter failures;
    private final boolean seedHashes;
    private final Cycle cycle;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> polling;
    private byte[] lastAttemptedHash = new byte[0];
    private byte[] lastCommittedHash = new byte[0];
    private boolean warnedMissing = false;
    // the empty-file condition persists just like the missing-file one, so it gets the
    // same latch: a truncated file at a 5s interval would otherwise warn forever
    private boolean warnedEmpty = false;
    private volatile boolean stale = false;

    FileWatchTrigger(final Logger log,
                     final Path location,
                     final Duration interval,
                     final String threadName,
                     final Messages messages,
                     final MetricRegistry metrics,
                     final String metricPrefix,
                     final Counter failures,
                     final boolean seedHashes,
                     final Cycle cycle) {
        this.log = Objects.requireNonNull(log);
        this.location = Objects.requireNonNull(location);
        this.interval = Objects.requireNonNull(interval);
        this.threadName = Objects.requireNonNull(threadName);
        this.messages = Objects.requireNonNull(messages);
        this.metrics = Objects.requireNonNull(metrics);
        this.metricPrefix = Objects.requireNonNull(metricPrefix);
        this.failures = Objects.requireNonNull(failures);
        this.seedHashes = seedHashes;
        this.cycle = Objects.requireNonNull(cycle);
    }

    /**
     * Seeds (if asked), schedules, then registers the gauges — in that order, because
     * the gauges exist only when a schedule exists (absence is the honest disabled
     * signal) and the dead gauge relies on a non-null handle.
     *
     * @param staleValue the staleness gauge, supplied by the owner: the config reloader
     *     reads a partial-reload state on top of {@link #isStale()}
     */
    void start(final Gauge<Integer> staleValue) {
        if (this.seedHashes) {
            seedHashesFromCurrentContent();
        }
        final long millis = this.interval.toMillis();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, this.threadName));
        // The handle is kept and cancelled explicitly rather than discarded. poll() swallows every
        // Exception itself, so a bad reload cycle cannot silently cancel the schedule and leave
        // hot-reload dead for the process lifetime — and the dead gauge below is what finally
        // makes that visible: an Error (the realistic one: OOM on an oversized file mid-read)
        // still propagates and cancels the task, deliberately — catching Throwable and marching
        // on would hide a process in real trouble. Fail-visible, not resilience theater
        this.polling = this.executor.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
        registerGauge(MetricRegistry.name(this.metricPrefix, "reload", "stale"), staleValue);
        registerGauge(MetricRegistry.name(this.metricPrefix, "reload", "dead"),
                () -> this.polling.isDone() ? 1 : 0);
    }

    /**
     * Remove-then-register, NOT Dropwizard's get-or-create {@code gauge(name, supplier)}:
     * get-or-create would hand a restarted bean (devtools, cached test contexts) the OLD
     * bean's gauge lambda, permanently reading dead fields. Plain register threw instead.
     */
    private void registerGauge(final String name, final Gauge<Integer> gauge) {
        this.metrics.remove(name);
        this.metrics.register(name, gauge);
    }

    /**
     * Seeds both hashes from the content that is already serving, so the first cycle
     * does not spuriously recommit an unchanged file. Best-effort: a read failure leaves
     * the hashes empty and the first poll re-parses, which is safe. An edit racing this
     * read (between boot's load and here) would be missed until the content changes
     * again, a sub-second window at startup, accepted.
     */
    private void seedHashesFromCurrentContent() {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(this.location));
            this.lastAttemptedHash = hash;
            this.lastCommittedHash = hash;
        } catch (final Exception e) {
            this.log.debug("Could not seed reload hashes from {}: {}", this.location, e.getMessage());
        }
    }

    void stop() {
        if (this.polling != null) {
            this.polling.cancel(true);
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    /**
     * One cycle. Never throws: no Exception from the read, from the owner's commit path
     * or from the owner's idle hook escapes, because a throwing scheduled task silently
     * cancels the schedule and leaves hot-reload dead for the process lifetime. An
     * {@code Error} still propagates and cancels it, deliberately — the dead gauge is
     * what makes that corpse visible.
     */
    void poll() {
        if (Thread.currentThread().isInterrupted()) {
            // orderly shutdown: polling.cancel(true) interrupts this thread, and a cycle
            // that begins interrupted must not read, count, or latch anything — counting
            // it corrupted the failure counter's meaning for alerting (#539). No idle
            // hook either: shutdown is not the moment to retry anything
            this.log.debug(this.messages.interruptedBeforePoll());
            return;
        }
        try {
            if (!Files.isRegularFile(this.location)) {
                if (!this.warnedMissing) {
                    this.log.warn(this.messages.missingFile());
                    this.warnedMissing = true;
                }
                // a pending partial heals from a file this branch says nothing about —
                // suspending the retry here would falsify the commit-time WARN's
                // "retried every poll" exactly in the degraded states
                runIdle();
                return;
            }
            this.warnedMissing = false;

            final byte[] content;
            try {
                content = Files.readAllBytes(this.location);
            } catch (final NoSuchFileException e) {
                // vanished between the check and the read: an atomic rm+mv replacement
                // or a symlink swap, the healthy deploy this class expects
                runIdle();
                return;
            }
            if (isBlank(content)) {
                // a shell '>' redirect truncates before writing, and editors flush
                // whitespace-only intermediate states: indistinguishable from an
                // intentionally emptied file; never commit on empty or blank
                if (!this.warnedEmpty) {
                    this.log.warn(this.messages.blankFile());
                    this.warnedEmpty = true;
                }
                runIdle();
                return;
            }
            this.warnedEmpty = false;
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            if (MessageDigest.isEqual(hash, this.lastAttemptedHash)) {
                // unchanged, or the same bad content we already warned about; staleness
                // reflects whether the file matches what is running (a transient read
                // failure must not latch the gauge)
                this.stale = !MessageDigest.isEqual(hash, this.lastCommittedHash);
                runIdle();
                return;
            }
            this.lastAttemptedHash = hash;

            this.cycle.onContent(content);
        } catch (final Exception e) {
            if (e instanceof ClosedByInterruptException || Thread.currentThread().isInterrupted()) {
                // the belt for an interrupt delivered mid-cycle (the check above catches
                // one already pending): same shutdown, same silence, and no counter moves.
                // A pre-set flag does not fault the read on this JDK, so through the
                // reloaders this branch used to be reachable only by a real race — but the
                // Cycle seam makes it deterministic from the commit path, and both arms
                // are pinned by FileWatchTriggerTest
                Thread.currentThread().interrupt();
                this.log.debug(this.messages.interruptedMidCycle(), e.getMessage());
                return;
            }
            this.failures.inc();
            this.stale = true;
            this.cycle.onFailure(e);
            // a rejected candidate neither supersedes nor retries whatever the owner has
            // pending, so without this a continuously churning broken file would starve
            // the retry while the commit-time WARN promises it
            runIdle();
        }
    }

    /**
     * The idle hook, with its throw contained. Two things must not happen when the
     * owner's idle work fails: it must not escape {@code poll()} and cancel the schedule,
     * and it must not be reported as a reload failure — this cycle read nothing, and a
     * cycle that read nothing must never move {@code *.reload.failures} or latch the
     * stale gauge (#539). Containing it here rather than letting the outer catch see it
     * also keeps the hook from being invoked twice for one cycle.
     */
    private void runIdle() {
        // RuntimeException, not Exception: onIdle declares no checked exception, so this
        // is the complete set — and a ClosedByInterruptException arm here would be dead
        // code the moment it was written
        try {
            this.cycle.onIdle();
        } catch (final RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // shutdown landing inside the hook: the same silence as every other
                // interrupt path, and nothing counted
                Thread.currentThread().interrupt();
                this.log.debug(this.messages.interruptedMidCycle(), e.getMessage());
                return;
            }
            this.log.warn(this.messages.idleFailure(), e.getMessage(), e);
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

    /** The content just handed to {@link Cycle#onContent} is now what is serving. */
    void markCommitted() {
        this.lastCommittedHash = this.lastAttemptedHash;
    }

    /**
     * Un-attempts the current content, so the next cycle re-reads it. For an owner that
     * declines a candidate for a reason that will not hold next cycle (the inventory
     * watcher's deferral, where the profiles changed mid-parse).
     */
    void rollbackAttempt() {
        this.lastAttemptedHash = this.lastCommittedHash;
    }

    /** Owner-decided staleness: a refusal latches it, a publish clears it. */
    void setStale(final boolean value) {
        this.stale = value;
    }

    /** Whether the file on disk differs from what is serving. */
    boolean isStale() {
        return this.stale;
    }
}
