/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reading of the nl6 instrument does when nl6 misreports (#662).
 *
 * <p>Both behaviours pinned here were defects reaching the e2e awaits. A missing collector answered
 * {@code 0} and read as a dip; a failed status call threw out of the {@code LongSupplier} and failed
 * the run outright, attributing an instrument hiccup to the ingest under test.</p>
 *
 * <p>Unit tests, deliberately: this is a property of the reading, not of Docker, so {@code make jar}
 * gates it rather than {@code make e2e} being the only thing that would catch a regression.</p>
 */
@Timeout(30)
class LedgerReadingTest {

    /**
     * A reading that fails contributes nothing, rather than propagating out of the wait.
     *
     * <p>The exception is checked on purpose: {@code Source.read} declares {@code throws Exception}
     * for the {@code IOException} and {@code InterruptedException} that {@code HttpClient.send}
     * raises, and this is the case that catch exists for. Were {@code advanced} to rethrow, the
     * throw fails this test on its own; it would reach {@code awaitCount}, which catches nothing,
     * and fail the run blaming ingest for an nl6 hiccup.</p>
     */
    @Test
    void aFailedReadHoldsTheLastValueInsteadOfThrowing() {
        final long held = LedgerReading.advanced(41L, () -> {
            throw new IOException("Connection refused");
        }, "sent_records for netflow9");

        assertThat(held)
                .as("a failed instrument read is not evidence about the ingest, so the previous"
                        + " high-water mark stands")
                .isEqualTo(41L);
    }

    /**
     * An interrupt is not an instrument hiccup, and must not be absorbed as one.
     *
     * <p>{@code InterruptedException} is an {@code Exception} too. Swallowing it would leave the
     * wait polling until its stall budget; restoring the flag lets the wait's next
     * {@code Thread.sleep} stop it.</p>
     */
    @Test
    void anInterruptedReadRestoresTheInterruptFlag() {
        try {
            final long held = LedgerReading.advanced(41L, () -> {
                throw new InterruptedException("test interrupt");
            }, "sent_records for netflow9");

            assertThat(held).as("the mark still stands; only the flag is restored").isEqualTo(41L);
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt must survive the catch, or the wait cannot be stopped")
                    .isTrue();
        } finally {
            // Clear the flag so it does not leak into the next test on this thread.
            Thread.interrupted();
        }
    }

    /**
     * A reading never goes backwards, so nl6's {@code 0} for a missing collector cannot dip.
     *
     * <p>This is the case that reached {@code awaitCount} as a decrease. Absorbing it here is what
     * lets that helper's dip tolerance be a backstop rather than the fix.</p>
     */
    @Test
    void aLowerReadDoesNotLowerTheReading() {
        assertThat(LedgerReading.advanced(1280L, () -> 0L, "sent_records for netflow9"))
                .as("nl6 answers 0 with no error when the status reply lists no collector")
                .isEqualTo(1280L);
    }

    /** A higher read is the whole point: the reading has to still advance. */
    @Test
    void aHigherReadAdvancesTheReading() {
        assertThat(LedgerReading.advanced(1280L, () -> 1281L, "sent_records for netflow9"))
                .as("holding the high-water mark must not mean freezing it, or every wait stalls")
                .isEqualTo(1281L);
    }

    /**
     * A run of failures still lets the count advance once the instrument recovers.
     *
     * <p>The failure path must hold the mark, not latch it: an nl6 that hiccups and comes back has
     * to leave the wait able to finish, or tolerating the hiccup merely moves the false failure from
     * an exception to a stall.</p>
     */
    @Test
    void aReadingRecoversAfterTheInstrumentDoes() {
        final var reads = new AtomicInteger();
        final LedgerReading.Source flaky = () -> {
            if (reads.incrementAndGet() < 3) {
                throw new IllegalStateException("nl6 status unreachable");
            }
            return 500L;
        };

        long reading = 100L;
        for (int i = 0; i < 4; i++) {
            reading = LedgerReading.advanced(reading, flaky, "sent_records for netflow9");
        }

        assertThat(reading).as("once nl6 answers again the reading has to advance").isEqualTo(500L);
        assertThat(reads.get()).as("every call must attempt a read; a latched failure never recovers").isEqualTo(4);
    }

    /**
     * An instance keeps one mark per protocol, starting at {@code 0}.
     *
     * <p>This is the wiring {@code Nl6Container.ledger} delegates to. Before it lived here it was
     * only reached by the e2e suites against a healthy nl6, so it could regress back to the raw read
     * with every gate green.</p>
     */
    @Test
    void aMarkIsKeptPerProtocol() {
        final var ledger = new LedgerReading();

        assertThat(ledger.advance("netflow9", () -> 10L)).isEqualTo(10L);
        assertThat(ledger.advance("ipfix", () -> 0L))
                .as("a protocol never read starts at 0, not at another protocol's mark")
                .isEqualTo(0L);
        assertThat(ledger.advance("netflow9", () -> 0L))
                .as("a missing collector answers 0; the netflow9 mark must hold")
                .isEqualTo(10L);
        assertThat(ledger.advance("netflow9", () -> {
            throw new IOException("Connection refused");
        })).as("a failed read holds the mark for its own protocol").isEqualTo(10L);
        assertThat(ledger.advance("netflow9", () -> 11L)).isEqualTo(11L);
    }
}
