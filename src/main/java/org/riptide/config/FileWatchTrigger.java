/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.slf4j.Logger;

import java.io.IOException;
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
 * <p><b>Trigger</b>: an mtime-independent content-hash poll — the source is re-resolved
 * every cycle, so docker bind mounts and Kubernetes ConfigMap symlink swaps are seen
 * where a {@code WatchService} would miss them.</p>
 *
 * <p><b>Source-agnostic since #655</b>: what the source has to answer is one
 * {@link Fetch} per cycle, and the three answers are exactly the three states the loop
 * below distinguishes — {@link Fetch.Present}, {@link Fetch.Absent} and
 * {@link Fetch.Vanished}. Only a file source ever answers the third: a file that
 * disappears between the existence check and the read is an atomic {@code rm}+{@code mv}
 * replacement, which is silent, while a file that is simply not there warns once. A
 * remote source cannot tell those apart, answers {@code Absent} for a 404, and never has
 * to pretend otherwise. The loop asks for exactly one fetch per cycle — not the two an
 * {@code exists()}-then-{@code read()} seam would have cost — though an owner whose commit
 * path re-reads the source pays for that read itself, on top of this one.</p>
 *
 * <p>The class keeps its name: the file reloaders and their suites are the regression
 * gate for this seam and name it everywhere. The {@code Path} constructor below is the
 * file source they still get; the classification rule reloader (#655) hands in an
 * {@code http(s)://} one and reuses everything else.</p>
 */
public final class FileWatchTrigger {

    /**
     * What one fetch of the watched source found. Sealed, because the loop's three
     * source-touching branches are its three cases and a fourth state would be a fourth
     * branch nobody wrote.
     */
    public sealed interface Fetch {

        /**
         * The source answered with content; blankness and change detection follow.
         *
         * <p>The array is handed over, not copied, and the trigger passes that same array
         * to {@link Cycle#onContent}: a source must not keep or reuse a buffer it has
         * answered with. Identity {@code equals}/{@code hashCode} come with that — two
         * {@code Present} values are never compared here, and content comparison is the
         * hash's job.</p>
         */
        record Present(byte[] content) implements Fetch { }

        /** The source is not there: a missing file, a 404. Warned once per episode. */
        record Absent() implements Fetch { }

        /**
         * The source was there and was gone by the time it was read — an atomic
         * replacement in progress. Silent, and only a file source can answer it.
         */
        record Vanished() implements Fetch { }
    }

    /** The watched thing, reduced to what the loop needs of it. */
    public interface Source {

        /**
         * One fetch. A throw is the failure path: counted, latched, described by the
         * owner. Absence is not a throw — it is {@link Fetch.Absent}, which skips.
         *
         * <p>{@code IOException}, not {@code Exception}: reading a source is I/O, both
         * implementations narrow to it, and a wider signature would push every caller of
         * a {@code Source} into catching things no source can raise.</p>
         */
        Fetch fetch() throws IOException;

        /** How the source is named in the trigger's own DEBUG output. */
        String describe();
    }

    /**
     * What the owner does with a cycle the trigger has already classified.
     *
     * <p>{@code onIdle} is invoked from exactly the branches that read nothing new —
     * absent source, vanished-between-check-and-read, blank source, unchanged content,
     * and a counted failure — and from neither interrupt branch. That asymmetry is the
     * contract that keeps the config reloader's pending-rebuild retry healing while the
     * watched file is missing, truncated or churning invalid. Four of the five are pinned
     * by {@code FileWatchTriggerTest} against a real file; the vanish branch used to be
     * unschedulable there — the file has to disappear between the existence check and the
     * read — and is pinned by {@code FileWatchTriggerSourceTest} since the {@link Source}
     * seam made it something a source can simply answer (#655).</p>
     */
    public interface Cycle {
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
     * @param missingSource WARN emitted once while the source is not there
     * @param blankSource WARN emitted once while the source reads empty or whitespace-only
     * @param idleFailure WARN for a throwing {@link Cycle#onIdle}; one {@code {}}
     *     placeholder, filled with the exception's message
     */
    public record Messages(String interruptedBeforePoll,
                    String interruptedMidCycle,
                    String missingSource,
                    String blankSource,
                    String idleFailure) {

        // every other collaborator is null-checked at construction; a null here would
        // instead surface as an NPE at log time, on the scheduler thread, inside a catch
        // or mid-shutdown — the three places least likely to be read
        public Messages {
            Objects.requireNonNull(interruptedBeforePoll, "interruptedBeforePoll");
            Objects.requireNonNull(interruptedMidCycle, "interruptedMidCycle");
            Objects.requireNonNull(missingSource, "missingSource");
            Objects.requireNonNull(blankSource, "blankSource");
            Objects.requireNonNull(idleFailure, "idleFailure");
        }
    }

    /** The file source both file reloaders watch: the only one that answers {@link Fetch.Vanished}. */
    private record FileSource(Path path) implements Source {

        @Override
        public Fetch fetch() throws IOException {
            if (!Files.isRegularFile(this.path)) {
                return new Fetch.Absent();
            }
            try {
                return new Fetch.Present(Files.readAllBytes(this.path));
            } catch (final NoSuchFileException e) {
                // vanished between the check and the read: an atomic rm+mv replacement
                // or a symlink swap, the healthy deploy this class expects
                return new Fetch.Vanished();
            }
        }

        @Override
        public String describe() {
            return this.path.toString();
        }
    }

    private final Logger log;
    private final Source source;
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

    /** The file source, for the two reloaders that watch a path. */
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
        this(log, new FileSource(Objects.requireNonNull(location)), interval, threadName, messages,
                metrics, metricPrefix, failures, seedHashes, cycle);
    }

    public FileWatchTrigger(final Logger log,
                     final Source source,
                     final Duration interval,
                     final String threadName,
                     final Messages messages,
                     final MetricRegistry metrics,
                     final String metricPrefix,
                     final Counter failures,
                     final boolean seedHashes,
                     final Cycle cycle) {
        this.log = Objects.requireNonNull(log);
        this.source = Objects.requireNonNull(source);
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
    public void start(final Gauge<Integer> staleValue) {
        if (this.seedHashes) {
            seedHashesFromCurrentContent();
        }
        final long millis = this.interval.toMillis();
        // Daemon: a poll parked in a socket read is not interruptible by cancel(true), and a
        // non-daemon thread in that state holds the JVM open past @PreDestroy for as long as the
        // fetch deadline allows. Harmless while every source was a local file; a reload source can
        // be a URL since #655.
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, this.threadName);
            thread.setDaemon(true);
            return thread;
        });
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
     * does not spuriously recommit an unchanged source. Best-effort: a failed or absent
     * fetch leaves the hashes empty and the first poll re-parses, which is safe. An edit
     * racing this fetch (between boot's load and here) would be missed until the content
     * changes again, a sub-second window at startup, accepted.
     */
    private void seedHashesFromCurrentContent() {
        try {
            if (this.source.fetch() instanceof Fetch.Present present) {
                final byte[] hash = MessageDigest.getInstance("SHA-256").digest(present.content());
                this.lastAttemptedHash = hash;
                this.lastCommittedHash = hash;
            }
        } catch (final Exception e) {
            this.log.debug("Could not seed reload hashes from {}: {}", this.source.describe(), e.getMessage());
        }
    }

    public void stop() {
        if (this.polling != null) {
            this.polling.cancel(true);
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
        // The gauges are deliberately NOT removed here, and #715 was closed by correcting the docs
        // instead. The reason is name-sharing, not rebinding: a gauge name is derived from the
        // metric prefix alone, so every trigger with that prefix registers and would remove the
        // same two names. registerGauge already removes-then-registers, so rebinding a restarted
        // bean works whether or not stop() cleaned up — what a by-name remove() here would buy is
        // the ability for a short-lived second instance to deregister a still-running first one's
        // gauges on its way out. Probed: adding the removal makes
        // FileWatchTriggerTest.aSecondTriggerRebindsTheGaugesToItself fail while the first trigger
        // is still scheduled and alive.
        //
        // Cleared so a stopped trigger can be started again.
        this.executor = null;
    }

    /**
     * One cycle. Never throws: no Exception from the read, from the owner's commit path
     * or from the owner's idle hook escapes, because a throwing scheduled task silently
     * cancels the schedule and leaves hot-reload dead for the process lifetime. An
     * {@code Error} still propagates and cancels it, deliberately — the dead gauge is
     * what makes that corpse visible.
     */
    public void poll() {
        if (Thread.currentThread().isInterrupted()) {
            // orderly shutdown: polling.cancel(true) interrupts this thread, and a cycle
            // that begins interrupted must not read, count, or latch anything — counting
            // it corrupted the failure counter's meaning for alerting (#539). No idle
            // hook either: shutdown is not the moment to retry anything
            this.log.debug(this.messages.interruptedBeforePoll());
            return;
        }
        try {
            final Fetch fetch = this.source.fetch();
            if (fetch instanceof Fetch.Absent) {
                if (!this.warnedMissing) {
                    this.log.warn(this.messages.missingSource());
                    this.warnedMissing = true;
                }
                // The blank latch re-arms here too. It clears only after a non-blank read, so
                // without this a blank -> absent -> blank sequence warned once and then went
                // silent: the file disappearing ends the blank episode, and its return blank is a
                // new one. warnedMissing has always re-armed this way (cleared below for Vanished
                // as well as Present); this is the half that did not.
                this.warnedEmpty = false;
                // a pending partial heals from a file this branch says nothing about —
                // suspending the retry here would falsify the commit-time WARN's
                // "retried every poll" exactly in the degraded states
                runIdle();
                return;
            }
            // cleared for Vanished too, not only for Present: the source WAS there this
            // cycle, so the next disappearance is a new episode and warns again
            this.warnedMissing = false;

            if (!(fetch instanceof Fetch.Present present)) {
                // vanished between the source's own check and its read: an atomic rm+mv
                // replacement or a symlink swap, the healthy deploy this class expects
                runIdle();
                return;
            }
            final byte[] content = present.content();
            if (isBlank(content)) {
                // a shell '>' redirect truncates before writing, and editors flush
                // whitespace-only intermediate states: indistinguishable from an
                // intentionally emptied file; never commit on empty or blank
                if (!this.warnedEmpty) {
                    this.log.warn(this.messages.blankSource());
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
        // A leading UTF-8 BOM is deliberately NOT treated as blank. Doing so closes only the rare
        // half — a file truncated to nothing but a BOM — while the common half, a BOM followed by
        // real content, still reaches the parser with U+FEFF on the front. It would also make the
        // blank WARN say "empty or whitespace-only" about a three-byte file, which is the exact
        // wild-goose chase InventoryLoader's own comment says that wording exists to prevent.
        for (int i = 0; i < content.length; i++) {
            final byte b = content[i];
            // Form feed and vertical tab too: both are whitespace an editor can flush mid-write,
            // and both used to reach the commit path.
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t' && b != '\f' && b != 0x0B) {
                return false;
            }
        }
        return true;
    }

    /** The content just handed to {@link Cycle#onContent} is now what is serving. */
    public void markCommitted() {
        this.lastCommittedHash = this.lastAttemptedHash;
    }

    /**
     * Un-attempts the current content, so the next cycle re-reads it. For an owner that
     * declines a candidate for a reason that will not hold next cycle (the inventory
     * watcher's deferral, where the profiles changed mid-parse).
     *
     * <p>Package-private while {@link #start}, {@link #poll} and {@link #markCommitted}
     * are public: this and {@link #setStale} encode owner-decided state that only the two
     * file reloaders have — a deferral, and a refusal the owner latches itself. The
     * classification owner defers nothing and does not decide its own staleness (the
     * engine does). Widen them when an owner outside this package needs one, not before.</p>
     */
    void rollbackAttempt() {
        this.lastAttemptedHash = this.lastCommittedHash;
    }

    /** Owner-decided staleness: a refusal latches it, a publish clears it. */
    void setStale(final boolean value) {
        this.stale = value;
    }

    /** Whether the watched source differs from what is serving. */
    public boolean isStale() {
        return this.stale;
    }
}
