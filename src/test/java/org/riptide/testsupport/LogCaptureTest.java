/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The positive control for {@link LogCapture}: a reader streaming the capture while another thread
 * is still logging into it.
 *
 * <p>A test that merely reads a quiet appender proves nothing here, because the bug needs a
 * concurrent producer — that is the whole shape of #735. Point {@link LogCapture#startedAppender()}
 * at a plain {@code ArrayList} and this fails, which is how it was checked.</p>
 */
@Timeout(60)
class LogCaptureTest {

    /**
     * How many lines the producer emits. Large enough that the reader below provably overlaps it
     * for thousands of passes; small enough that the copy-on-write cost is noise.
     */
    private static final int EVENTS = 5_000;

    @Test
    void aReaderStreamingTheCaptureWhileAnotherThreadLogsNeitherTearsNorLosesAnEvent() throws Exception {
        // its own logger, so nothing else in the suite can append to this capture, and non-additive
        // so 5,000 fixture lines stay fixture rather than becoming console output
        final var logger = (Logger) LoggerFactory.getLogger("org.riptide.testsupport.LogCaptureTest$producer");
        final boolean additive = logger.isAdditive();
        final Level level = logger.getLevel();
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);

        final var appender = LogCapture.startedAppender();
        // Deterministic, and cheap: these two say the factory did what it is named for, and they
        // fail on a revert immediately rather than waiting for a scheduling accident.
        assertThat(appender.list)
                .as("the capture's container is what makes a concurrent read safe")
                .isInstanceOf(CopyOnWriteArrayList.class);
        assertThat(appender.isStarted()).as("startedAppender() returns a started appender").isTrue();

        logger.addAppender(appender);
        final var failed = new AtomicReference<Throwable>();
        // Released by the reader once it has taken a read of the still-empty capture, so the
        // producer cannot finish before the reader has started. Without it the reader's first pass
        // can land after the last append, and every assertion below still passes over a quiet
        // appender — the exact vacuum this test exists to avoid.
        final var readerStarted = new CountDownLatch(1);
        final var producer = new Thread(() -> {
            try {
                readerStarted.await();
                for (int i = 0; i < EVENTS; i++) {
                    logger.info("event {}", i);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "log-capture-producer");
        producer.setUncaughtExceptionHandler((thread, thrown) -> failed.set(thrown));

        try {
            producer.start();

            // Exactly what the tests this helper serves do: stream the captured list while the
            // producer is still appending to it. Against a plain ArrayList that is the defect —
            // ConcurrentModificationException out of the spliterator.
            //
            // The filter is load-bearing, not decoration: count() on an unfiltered stream is
            // answered from the spliterator's reported size without traversing, so the list would
            // never be iterated and the race never exercised.
            long reads = 0;
            long smallestNonEmptyRead = Long.MAX_VALUE;
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (producer.isAlive()) {
                final long seen = appender.list.stream()
                        .filter(event -> event.getLevel() == Level.INFO).count();
                reads++;
                readerStarted.countDown();
                if (seen > 0) {
                    smallestNonEmptyRead = Math.min(smallestNonEmptyRead, seen);
                }
                if (System.nanoTime() > deadline) {
                    producer.interrupt();
                    throw new AssertionError("the producer never finished; test would otherwise hang");
                }
            }
            producer.join();

            assertThat(failed.get()).as("the producer finished cleanly").isNull();
            assertThat(reads).as("the reader ran while the producer was alive").isPositive();
            // The guard that carries the proof. A read that saw between one event and all of them
            // is a read that overlapped the producer mid-append, which is the only state in which
            // a plain ArrayList throws. Without this, a reader descheduled until after the last
            // append passes everything below having exercised nothing.
            assertThat(smallestNonEmptyRead)
                    .as("at least one read observed the capture mid-growth, not just its final state")
                    .isBetween(1L, (long) EVENTS - 1);

            assertThat(appender.list)
                    .as("the capture holds every event the producer emitted")
                    .hasSize(EVENTS);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("and one producer's events are observed in the order it appended them")
                    .containsExactlyElementsOf(IntStream.range(0, EVENTS).mapToObj(i -> "event " + i).toList());
        } finally {
            // Ordered so a failure above cannot leak the thread into the rest of the surefire JVM.
            readerStarted.countDown();
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(10));
            logger.detachAppender(appender);
            appender.stop();
            logger.setAdditive(additive);
            logger.setLevel(level);
        }
    }
}
