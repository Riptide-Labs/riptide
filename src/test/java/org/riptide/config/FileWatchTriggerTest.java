/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trigger's contract on its own, without either owner: the schedule, the hashes,
 * the skips and their latches, the failure counting and the interrupt discipline. The
 * two reloaders' suites can each only see their own copy of this loop, which is how the
 * two copies drifted apart unnoticed (#561) — every property here is one neither of
 * them could have caught.
 *
 * <p>The owner's collaborator role is played by a recording {@link FileWatchTrigger.Cycle}
 * and by this class's own logger: capturing on {@code FileWatchTriggerTest}'s category
 * is what proves the trigger speaks through the logger it was handed rather than one of
 * its own, which is the property that keeps both reloaders' log assertions working.</p>
 */
class FileWatchTriggerTest {

    @TempDir
    Path tempDir;

    private Path file;
    private MetricRegistry metrics;
    private Counter failures;
    private RecordingCycle cycle;
    private FileWatchTrigger trigger;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    private static final FileWatchTrigger.Messages MESSAGES = new FileWatchTrigger.Messages(
            "interrupted-before-poll",
            "interrupted-mid-cycle: {}",
            "the-file-is-missing",
            "the-file-is-blank",
            "the-idle-hook-failed: {}");

    /** Records what the owner was asked to do, and can be told to misbehave. */
    private static final class RecordingCycle implements FileWatchTrigger.Cycle {
        private final List<String> commits = new ArrayList<>();
        private final List<Exception> reported = new ArrayList<>();
        private int idles;
        /**
         * Thrown from the next {@code onContent}, standing in for a bad candidate. Typed
         * {@link Exception}, not {@code RuntimeException}: the checked-exception half is
         * the whole point of one of these tests, and narrowing it here would silently
         * make the mid-cycle interrupt belt unreachable from this suite.
         */
        private Exception throwOnContent;
        /** Thrown from the next {@code onIdle}: idle work that fails is not a reload failure. */
        private RuntimeException throwOnIdle;
        /** Run inside {@code onContent} after recording, for the hash-state assertions. */
        private Runnable duringContent = () -> { };

        @Override
        public void onContent(final byte[] content) throws Exception {
            this.commits.add(new String(content, StandardCharsets.UTF_8));
            this.duringContent.run();
            if (this.throwOnContent != null) {
                throw this.throwOnContent;
            }
        }

        @Override
        public void onIdle() {
            this.idles++;
            if (this.throwOnIdle != null) {
                throw this.throwOnIdle;
            }
        }

        @Override
        public void onFailure(final Exception e) {
            this.reported.add(e);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        this.file = this.tempDir.resolve("watched.yaml");
        write("first: content\n");
        this.metrics = new MetricRegistry();
        this.failures = this.metrics.counter("test.reload.failures");
        this.cycle = new RecordingCycle();
        this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FileWatchTriggerTest.class);
        // the interrupt discipline speaks at DEBUG, so the capture has to be able to hear it
        this.logger.setLevel(Level.DEBUG);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void tearDown() {
        if (this.trigger != null) {
            this.trigger.stop();
        }
        this.logger.detachAppender(this.appender);
        this.appender.stop();
        this.logger.setLevel(null);
    }

    /** Builds and starts a trigger; {@code seeded} is the hash-seeding divergence, as a parameter. */
    private FileWatchTrigger start(final boolean seeded) {
        this.trigger = new FileWatchTrigger(this.logger, this.file, Duration.ofHours(1),
                "FileWatchTriggerTest", MESSAGES, this.metrics, "test", this.failures, seeded, this.cycle);
        this.trigger.start(() -> this.trigger.isStale() ? 1 : 0);
        return this.trigger;
    }

    // -- the matrix ------------------------------------------------------------------

    /**
     * A whitespace-only file is a benign skip, not a failure. This is the divergence
     * #561 settled: the config reloader tested {@code content.length == 0}, so a file
     * holding two spaces reached its commit path and was counted as a reload failure
     * with the stale gauge latched — for a shape the docs call a skip.
     */
    @Test
    void aWhitespaceOnlyFileSkipsWithoutFailingOrLatching() throws IOException {
        start(false);
        write("  \n\t\r\n   ");

        this.trigger.poll();

        assertThat(this.cycle.commits).as("blank content must never reach the commit path").isEmpty();
        assertThat(this.failures.getCount()).isZero();
        assertThat(stale()).isZero();
        assertThat(this.cycle.idles).as("the idle hook still runs").isEqualTo(1);
        assertThat(warnings()).containsExactly("the-file-is-blank");
    }

    /**
     * The other half of that divergence: the config reloader had no {@code warnedEmpty}
     * latch, so a truncated file warned on every single poll — forever, at whatever the
     * reload interval is. Warn once, then silence, and re-arm when content returns.
     */
    @Test
    void aTruncatedFileWarnsOnceAndTheLatchReArmsWhenContentReturns() throws IOException {
        start(false);
        write("");

        for (int i = 0; i < 10; i++) {
            this.trigger.poll();
        }

        assertThat(warnings()).as("ten polls, one warning").containsExactly("the-file-is-blank");
        assertThat(this.cycle.idles).isEqualTo(10);

        // content returns: the file is read normally, and the latch clears
        write("back: again\n");
        this.trigger.poll();
        assertThat(this.cycle.commits).containsExactly("back: again\n");

        // and truncated again warns again — the latch is per-episode, not once per process
        write("   \n");
        this.trigger.poll();
        assertThat(warnings()).containsExactly("the-file-is-blank", "the-file-is-blank");
    }

    /** Missing file: warn once behind the latch, run the idle hook, never commit. */
    @Test
    void aMissingFileWarnsOnceAndStillRunsTheIdleHook() throws IOException {
        start(false);
        Files.delete(this.file);

        this.trigger.poll();
        this.trigger.poll();
        this.trigger.poll();

        assertThat(warnings()).containsExactly("the-file-is-missing");
        assertThat(this.cycle.idles).as("the idle hook runs on every missing cycle").isEqualTo(3);
        assertThat(this.cycle.commits).isEmpty();
        assertThat(this.failures.getCount()).isZero();

        // reappearing is picked up, and a second disappearance warns again
        write("returned: yes\n");
        this.trigger.poll();
        assertThat(this.cycle.commits).containsExactly("returned: yes\n");
        Files.delete(this.file);
        this.trigger.poll();
        assertThat(warnings()).containsExactly("the-file-is-missing", "the-file-is-missing");
    }

    /**
     * Unchanged content short-circuits, and staleness is recomputed against the
     * <em>committed</em> hash, not the attempted one. Neither reloader's suite can see
     * this on its own: it takes an owner that attempts without committing, which is what
     * a refused candidate is.
     */
    @Test
    void unchangedContentShortCircuitsAndRecomputesStaleAgainstTheCommittedHash() throws IOException {
        start(false);
        write("attempted: never-committed\n");

        // an owner that reads the content but declines to commit it
        this.trigger.poll();
        assertThat(this.cycle.commits).hasSize(1);
        assertThat(stale()).as("the recompute happens on the NEXT cycle, not this one").isZero();

        this.trigger.poll();
        assertThat(this.cycle.commits).as("attempted once").hasSize(1);
        assertThat(this.cycle.idles).isEqualTo(1);
        assertThat(stale()).as("the file does not match what is serving").isEqualTo(1);

        // now an owner that commits: the same unchanged content reads as in sync
        write("committed: yes\n");
        this.cycle.duringContent = () -> this.trigger.markCommitted();
        this.trigger.poll();
        this.trigger.poll();
        assertThat(stale()).isZero();
    }

    /**
     * A poll that begins interrupted is shutdown: it reads nothing, counts nothing,
     * latches nothing — and, unlike every other skip, does <em>not</em> run the idle
     * hook. That asymmetry is the contract; the config reloader's healing retry hangs
     * off the five branches that do run it.
     */
    @Test
    void aPollBeginningInterruptedConsumesNothingAndSkipsTheIdleHook() throws IOException {
        start(false);
        write("unconsumed: content\n");

        Thread.currentThread().interrupt();
        try {
            this.trigger.poll();
        } finally {
            Thread.interrupted();
        }

        assertThat(this.cycle.commits).isEmpty();
        assertThat(this.cycle.idles).as("shutdown is not the moment to retry anything").isZero();
        assertThat(this.failures.getCount()).isZero();
        assertThat(stale()).isZero();
        assertThat(debugs()).containsExactly("interrupted-before-poll");

        // the content was never consumed, so the next clean poll serves it normally
        this.trigger.poll();
        assertThat(this.cycle.commits).containsExactly("unconsumed: content\n");
    }

    /**
     * A commit that throws: the trigger counts the failure and latches staleness, hands
     * the exception to the owner to describe, and says nothing itself — the sentence
     * lives on the owner so its own test can keep capturing it on its own category.
     */
    @Test
    void aThrowingCommitIsCountedAndLatchedButNeverDescribedByTheTrigger() throws IOException {
        start(false);
        final RuntimeException boom = new IllegalStateException("candidate is nonsense");
        this.cycle.throwOnContent = boom;
        write("bad: candidate\n");

        this.trigger.poll();

        assertThat(this.failures.getCount()).isEqualTo(1);
        assertThat(stale()).isEqualTo(1);
        assertThat(this.cycle.reported).containsExactly(boom);
        assertThat(this.cycle.idles).as("a rejected candidate must not starve the owner's retry").isEqualTo(1);
        assertThat(this.appender.list)
                .as("the trigger counts the failure; the owner is the one that describes it")
                .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }

    /**
     * An interrupt delivered <em>during</em> the commit — the belt in the catch — is the
     * same shutdown as one already pending at the top: silence, no counter, no latch, no
     * idle hook, and the flag restored for whatever unwinds next. Through the reloaders
     * this branch was reachable only by a real race and stood untested for that reason;
     * the {@code Cycle} seam makes it deterministic.
     */
    @Test
    void aClosedByInterruptFromTheCommitIsShutdownNotAFailure() throws IOException {
        start(false);
        this.cycle.throwOnContent = new ClosedByInterruptException();
        write("interrupted: mid-cycle\n");

        this.trigger.poll();
        final boolean reInterrupted = Thread.interrupted();

        assertThat(reInterrupted).as("the flag is restored for the caller unwinding after us").isTrue();
        assertThat(this.failures.getCount()).as("shutdown is not a reload failure").isZero();
        assertThat(stale()).isZero();
        assertThat(this.cycle.reported).isEmpty();
        assertThat(this.cycle.idles).isZero();
        assertThat(warnings()).isEmpty();
        assertThat(debugs()).containsExactly("interrupted-mid-cycle: null");
    }

    /**
     * The other arm of the same belt — the flag set mid-cycle, with an ordinary exception
     * riding out on it — and the one place a message placeholder is filled. The mid-cycle
     * sentence carries the only {@code {}} of the five: if it were swapped with the
     * before-poll sentence, the exception's message would be silently dropped, and both
     * still being DEBUG is what would hide it.
     */
    @Test
    void anInterruptSetDuringTheCommitIsShutdownAndTheDebugCarriesTheMessage() throws IOException {
        start(false);
        this.cycle.duringContent = () -> Thread.currentThread().interrupt();
        this.cycle.throwOnContent = new IllegalStateException("candidate read faulted");
        write("interrupted: by-flag\n");

        this.trigger.poll();
        Thread.interrupted();

        assertThat(this.failures.getCount()).isZero();
        assertThat(stale()).isZero();
        assertThat(this.cycle.reported).isEmpty();
        assertThat(debugs())
                .as("the placeholder is filled from the exception, not left empty")
                .containsExactly("interrupted-mid-cycle: candidate read faulted");
    }

    /**
     * Idle work that throws is not a reload failure and must not escape. Before this was
     * contained, an idle throw on a cycle that read nothing fell into the outer catch:
     * it moved {@code *.reload.failures}, latched the stale gauge and called
     * {@code onFailure} for a cycle with no candidate, then ran the hook a second time —
     * and from the catch's own trailing call it escaped {@code poll()} outright, which
     * cancels the schedule and kills hot-reload for the process lifetime.
     */
    @Test
    void aThrowingIdleHookIsContainedAndNeverCountedAsAReloadFailure() throws IOException {
        start(false);
        Files.delete(this.file);
        this.cycle.throwOnIdle = new IllegalStateException("retry blew up");

        this.trigger.poll();

        assertThat(this.failures.getCount())
                .as("a cycle that read nothing must never move the failure counter").isZero();
        assertThat(stale()).isZero();
        assertThat(this.cycle.reported).as("this is not a reload failure").isEmpty();
        assertThat(this.cycle.idles).as("invoked once for this cycle, not twice").isEqualTo(1);
        assertThat(warnings()).contains("the-idle-hook-failed: retry blew up");
    }

    /** The same containment on the failure path, where the throw used to escape poll() outright. */
    @Test
    void aThrowingIdleHookOnTheFailurePathDoesNotEscapePoll() throws IOException {
        start(false);
        this.cycle.throwOnContent = new IllegalStateException("candidate is nonsense");
        this.cycle.throwOnIdle = new IllegalStateException("retry blew up");
        write("bad: candidate\n");

        this.trigger.poll();

        // the commit failure is counted exactly once and described once; the idle throw
        // rides alongside it without escaping and without inflating the counter
        assertThat(this.failures.getCount()).isEqualTo(1);
        assertThat(this.cycle.reported).hasSize(1);
        assertThat(this.cycle.idles).isEqualTo(1);
        assertThat(warnings()).contains("the-idle-hook-failed: retry blew up");
    }

    /** The same bad content is attempted once; later polls are idle, not repeated failures. */
    @Test
    void theSameBadContentIsAttemptedOnlyOnce() throws IOException {
        start(false);
        this.cycle.throwOnContent = new IllegalStateException("candidate is nonsense");
        write("bad: candidate\n");

        this.trigger.poll();
        this.trigger.poll();
        this.trigger.poll();

        assertThat(this.failures.getCount()).isEqualTo(1);
        assertThat(this.cycle.reported).hasSize(1);
        assertThat(this.cycle.idles)
                .as("idle on the failing cycle and on both short-circuited ones").isEqualTo(3);
        assertThat(stale()).isEqualTo(1);
    }

    // -- the per-cycle properties neither reloader's suite can reach ------------------

    /**
     * Hash seeding is a construction parameter, not a decision the trigger makes: the
     * inventory watcher seeds from the content boot already served, so its first poll
     * does not recommit it, and the config reloader deliberately does not, so a file
     * created after boot reaches the running configuration on the first cycle. Both
     * behaviours, side by side, against the same file.
     */
    @Test
    void seedingIsTheOwnersChoiceAndDecidesWhetherTheFirstPollCommits() {
        start(true);
        this.trigger.poll();
        assertThat(this.cycle.commits).as("seeded: the boot content is already serving").isEmpty();

        this.trigger.stop();
        this.cycle = new RecordingCycle();
        start(false);
        this.trigger.poll();
        assertThat(this.cycle.commits).as("unseeded: the first poll commits what it finds")
                .containsExactly("first: content\n");
    }

    /**
     * {@code rollbackAttempt} un-attempts the current content so the next cycle re-reads
     * it — the inventory watcher's deferral, where the profiles changed mid-parse. Without
     * it the unchanged-content short-circuit would swallow the candidate forever.
     */
    @Test
    void rollbackAttemptMakesTheNextCycleReReadTheSameContent() throws IOException {
        start(false);
        write("deferred: candidate\n");
        this.cycle.duringContent = () -> this.trigger.rollbackAttempt();

        this.trigger.poll();
        this.trigger.poll();

        assertThat(this.cycle.commits)
                .as("the deferred candidate is offered again, unchanged")
                .containsExactly("deferred: candidate\n", "deferred: candidate\n");
    }

    /**
     * The gauges exist only once a schedule does, they carry the owner's metric prefix,
     * and the dead one reports a cancelled schedule — the corpse an {@code Error} out of
     * {@code poll()} would leave behind.
     */
    @Test
    void gaugesRegisterUnderTheOwnersPrefixAndTheDeadOneSeesACancelledSchedule() {
        assertThat(this.metrics.getGauges()).doesNotContainKeys("test.reload.stale", "test.reload.dead");

        start(false);
        assertThat(this.metrics.getGauges()).containsKeys("test.reload.stale", "test.reload.dead");
        assertThat(dead()).as("a live schedule is not a corpse").isZero();
        // and the poll thread carries the name it was handed. threadName and metricPrefix
        // are adjacent String constructor arguments: swapping them compiles, and without
        // this the only symptom would be gauges named after the owner class
        assertThat(Thread.getAllStackTraces().keySet())
                .as("the scheduler thread is named for its owner")
                .anyMatch(thread -> "FileWatchTriggerTest".equals(thread.getName()));

        this.trigger.stop();
        assertThat(dead()).as("a cancelled schedule is a visible corpse").isEqualTo(1);
    }

    /**
     * Remove-then-register, not Dropwizard's get-or-create: a second trigger on the same
     * registry rebinds the gauges to itself. Get-or-create would leave the first
     * instance's lambda reading fields nothing writes to any more.
     */
    @Test
    void aSecondTriggerRebindsTheGaugesToItself() {
        final FileWatchTrigger first = start(false);
        assertThat(dead()).isZero();

        final RecordingCycle second = new RecordingCycle();
        final FileWatchTrigger restarted = new FileWatchTrigger(this.logger, this.file, Duration.ofHours(1),
                "FileWatchTriggerTest", MESSAGES, this.metrics, "test", this.failures, false, second);
        restarted.start(() -> restarted.isStale() ? 1 : 0);
        restarted.stop();

        // first is still scheduled, so a 1 here can only be read off the restarted one
        assertThat(dead()).isEqualTo(1);
        first.stop();
    }

    // -- helpers ---------------------------------------------------------------------

    private void write(final String content) throws IOException {
        Files.writeString(this.file, content);
    }

    private int stale() {
        return (Integer) ((Gauge<?>) this.metrics.getGauges().get("test.reload.stale")).getValue();
    }

    private int dead() {
        return (Integer) ((Gauge<?>) this.metrics.getGauges().get("test.reload.dead")).getValue();
    }

    private List<String> warnings() {
        return this.appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> debugs() {
        return this.appender.list.stream()
                .filter(event -> event.getLevel() == Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
