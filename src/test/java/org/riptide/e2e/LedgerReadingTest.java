/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    /** A reading that fails contributes nothing, rather than propagating out of the wait. */
    @Test
    void aFailedReadHoldsTheLastValueInsteadOfThrowing() {
        final Throwable thrown = catchThrowable(() -> {
            final long held = LedgerReading.advanced(41L, () -> {
                throw new IllegalStateException("nl6 status returned 503");
            }, "sent_records for netflow9");

            assertThat(held)
                    .as("a failed instrument read is not evidence about the ingest, so the previous"
                            + " high-water mark stands")
                    .isEqualTo(41L);
        });

        assertThat(thrown)
                .as("throwing here is the defect: it reaches awaitCount, which catches nothing, and"
                        + " fails the run blaming ingest for an nl6 hiccup")
                .isNull();
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

        assertThat(reading).isEqualTo(500L);
        assertThat(reads.get()).as("every call must attempt a read; a latched failure never recovers").isEqualTo(4);
    }
}
