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
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);

        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            final var producer = new Thread(() -> {
                for (int i = 0; i < EVENTS; i++) {
                    logger.info("event {}", i);
                }
            }, "log-capture-producer");
            producer.start();

            // Exactly what the tests this helper serves do: stream the captured list while the
            // producer is still appending to it. Against a plain ArrayList that is the defect —
            // ConcurrentModificationException out of the spliterator, or a torn read.
            long reads = 0;
            long seenOnTheLastRead = 0;
            while (producer.isAlive()) {
                seenOnTheLastRead = appender.list.stream().filter(event -> event.getLevel() == Level.INFO).count();
                reads++;
            }
            producer.join();

            // Both guards, because the race is what this test is: a reader that never ran, or ran
            // only against an empty capture, would pass the assertions below having proved nothing.
            assertThat(reads).as("the reader ran while the producer was alive").isPositive();
            assertThat(seenOnTheLastRead).as("and it was reading the growing capture, not an empty one").isPositive();

            assertThat(appender.list)
                    .as("no event was lost to the concurrent reads")
                    .hasSize(EVENTS);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("and one producer's events are observed in the order it appended them")
                    .containsExactlyElementsOf(IntStream.range(0, EVENTS).mapToObj(i -> "event " + i).toList());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
