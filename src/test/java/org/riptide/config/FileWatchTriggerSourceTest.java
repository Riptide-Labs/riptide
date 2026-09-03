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
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam #655 added: the trigger against a {@link FileWatchTrigger.Source} that is not
 * a file. {@code FileWatchTriggerTest} pins the same loop against a real file and is the
 * regression gate for the extraction; this one pins the properties only a source that can
 * be told what to answer can reach.
 *
 * <p>Chief among them the {@code Vanished} branch, which was verified by inspection for
 * as long as the source was a path: the file had to disappear between the existence check
 * and the read, and no test can schedule that. A source simply answers it.</p>
 */
class FileWatchTriggerSourceTest {

    private static final FileWatchTrigger.Messages MESSAGES = new FileWatchTrigger.Messages(
            "interrupted-before-poll",
            "interrupted-mid-cycle: {}",
            "the-source-is-absent",
            "the-source-is-blank",
            "the-idle-hook-failed: {}");

    /** A source told what to answer, per call. */
    private static final class ScriptedSource implements FileWatchTrigger.Source {
        private volatile FileWatchTrigger.Fetch next = new FileWatchTrigger.Fetch.Absent();
        private volatile IOException throwOnFetch;
        private int fetches;

        @Override
        public FileWatchTrigger.Fetch fetch() throws IOException {
            this.fetches++;
            if (this.throwOnFetch != null) {
                throw this.throwOnFetch;
            }
            return this.next;
        }

        @Override
        public String describe() {
            return "the-scripted-source";
        }

        private void present(final String content) {
            this.next = new FileWatchTrigger.Fetch.Present(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class RecordingCycle implements FileWatchTrigger.Cycle {
        private final List<String> commits = new ArrayList<>();
        private final List<Exception> reported = new ArrayList<>();
        private int idles;

        @Override
        public void onContent(final byte[] content) {
            this.commits.add(new String(content, StandardCharsets.UTF_8));
        }

        @Override
        public void onIdle() {
            this.idles++;
        }

        @Override
        public void onFailure(final Exception e) {
            this.reported.add(e);
        }
    }

    private final MetricRegistry metrics = new MetricRegistry();
    private final Counter failures = this.metrics.counter("test.reload.failures");
    private final ScriptedSource source = new ScriptedSource();
    private final RecordingCycle cycle = new RecordingCycle();

    private FileWatchTrigger trigger;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FileWatchTriggerSourceTest.class);
        this.logger.setLevel(Level.DEBUG);
        this.appender = LogCapture.startedAppender();
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

    private void start(final boolean seeded) {
        this.trigger = new FileWatchTrigger(this.logger, this.source, Duration.ofHours(1),
                "FileWatchTriggerSourceTest", MESSAGES, this.metrics, "test", this.failures, seeded, this.cycle);
        this.trigger.start(() -> this.trigger.isStale() ? 1 : 0);
    }

    /**
     * The branch that was unreachable while the source was a path: a replacement in
     * progress is silent, commits nothing, is not a failure, and still runs the idle hook.
     * Told apart from {@code Absent}, which warns — collapsing the two into one empty read
     * is exactly what the file reloaders must never start doing.
     */
    @Test
    void aVanishedSourceIsSilentAndCommitsNothing() {
        this.source.present("first: content\n");
        start(true);

        this.source.next = new FileWatchTrigger.Fetch.Vanished();
        this.trigger.poll();
        this.trigger.poll();

        assertThat(this.cycle.commits).isEmpty();
        assertThat(this.cycle.idles).as("a replacement in progress still runs the owner's idle work").isEqualTo(2);
        assertThat(this.failures.getCount()).as("an atomic replacement is not a reload failure").isZero();
        assertThat(stale()).isZero();
        assertThat(warnings()).as("silent, unlike an absent source").isEmpty();
    }

    /**
     * And the two are not the same latch: a source that vanishes re-arms the missing
     * warning, because it was there this cycle. A single flag shared by both branches
     * would swallow the warning for a source that really did go away.
     */
    @Test
    void vanishingReArmsTheAbsenceWarning() {
        this.source.present("first: content\n");
        start(true);

        this.trigger.poll();
        this.source.next = new FileWatchTrigger.Fetch.Absent();
        this.trigger.poll();
        this.trigger.poll();
        assertThat(warnings()).containsExactly("the-source-is-absent");

        this.source.next = new FileWatchTrigger.Fetch.Vanished();
        this.trigger.poll();
        this.source.next = new FileWatchTrigger.Fetch.Absent();
        this.trigger.poll();

        assertThat(warnings()).containsExactly("the-source-is-absent", "the-source-is-absent");
    }

    /** A source that throws is the failure path: counted, latched, handed to the owner. */
    @Test
    void aThrowingFetchIsCountedAndHandedToTheOwner() {
        this.source.present("first: content\n");
        start(true);

        final IOException boom = new IOException("connection refused");
        this.source.throwOnFetch = boom;
        this.trigger.poll();

        assertThat(this.failures.getCount()).isEqualTo(1);
        assertThat(stale()).isEqualTo(1);
        assertThat(this.cycle.reported).containsExactly(boom);
        assertThat(this.cycle.commits).isEmpty();
        assertThat(this.appender.list)
                .as("the trigger counts the failure; the owner is the one that describes it")
                .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));

        // and a later healthy fetch of the same content clears the latch
        this.source.throwOnFetch = null;
        this.trigger.poll();
        assertThat(stale()).as("the source matches what is serving again").isZero();
    }

    /** Seeding reads through the source, so a seeded first cycle does not recommit it. */
    @Test
    void seedingConsumesOneFetchFromTheSource() {
        this.source.present("already: serving\n");
        start(true);

        assertThat(this.source.fetches).as("seeding is one fetch, not a second one per poll").isEqualTo(1);
        this.trigger.poll();
        assertThat(this.cycle.commits).as("the seeded content is already serving").isEmpty();

        this.source.present("changed: content\n");
        this.trigger.poll();
        assertThat(this.cycle.commits).containsExactly("changed: content\n");
        assertThat(this.source.fetches).as("one fetch per cycle, never two").isEqualTo(3);
    }

    private int stale() {
        return (Integer) ((Gauge<?>) this.metrics.getGauges().get("test.reload.stale")).getValue();
    }

    private List<String> warnings() {
        return this.appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
