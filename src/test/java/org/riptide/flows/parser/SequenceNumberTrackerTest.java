/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import org.junit.jupiter.api.Test;
import org.assertj.core.api.Assertions;
import org.riptide.flows.parser.session.SequenceNumberTracker;

public class SequenceNumberTrackerTest {

    @Test
    void verifyInitZero() {
        final var tracker = new SequenceNumberTracker(32);
        Assertions.assertThat(tracker.verify(0)).isTrue();
    }

    @Test
    void verifyInitSmallerThanPatience() {
        final var tracker = new SequenceNumberTracker(32);
        Assertions.assertThat(tracker.verify(16)).isTrue();
    }

    @Test
    void verifyInitWithExactPatience() {
        final var tracker = new SequenceNumberTracker(32);
        Assertions.assertThat(tracker.verify(32)).isTrue();
    }

    @Test
    void verifyInitLargerThanPatience() {
        final var tracker = new SequenceNumberTracker(32);
        Assertions.assertThat(tracker.verify(128)).isTrue();
    }

    @Test
    void verifyInOrder() {
        final var tracker = new SequenceNumberTracker(32);
        for (int x = 0; x <= tracker.getPatience() * 2; x++) {
            Assertions.assertThat(tracker.verify(x)).isTrue();
        }
    }
    
    @Test
    void verifyOutOfOrder() {
        final var tracker = new SequenceNumberTracker(32);
        for (int x = 0; x <= tracker.getPatience() * 2; x += 2) {
            Assertions.assertThat(tracker.verify(x + 2)).describedAs("x=" + (x + 2)).isTrue();
            Assertions.assertThat(tracker.verify(x + 1)).describedAs("x=" + (x + 1)).isTrue();
        }
    }

    @Test
    void verifyDuplicates() {
        final var tracker = new SequenceNumberTracker(32);

        // Fill in 100 elements
        for (int x = 0; x <= 100; x++) {
            Assertions.assertThat(tracker.verify(x)).isTrue();
        }

        // Double call with current sequence number
        Assertions.assertThat(tracker.verify(100)).isTrue();

        // Double call with sequence number in history
        Assertions.assertThat(tracker.verify(90)).isTrue();
    }

    @Test
    void verifyLate() {
        final var tracker = new SequenceNumberTracker(32);

        // Start with first elements
        Assertions.assertThat(tracker.verify(95)).isTrue();
        Assertions.assertThat(tracker.verify(96)).isTrue();
        Assertions.assertThat(tracker.verify(97)).isTrue();
        Assertions.assertThat(tracker.verify(98)).isTrue();
        Assertions.assertThat(tracker.verify(99)).isTrue();

        // Skip the 100 and insert more elements to barely adhere to the patience
        for (int x = 1; x < tracker.getPatience(); x++) {
            Assertions.assertThat(tracker.verify(100 + x)).isTrue();
        }

        // 100 has not been seen and considered late
        Assertions.assertThat(tracker.verify(100 + tracker.getPatience())).isFalse();

        // Followings are there, again
        for (int x = 1; x < tracker.getPatience(); x++) {
            Assertions.assertThat(tracker.verify(100 + tracker.getPatience() + x)).isTrue();
        }
    }

    @Test
    void verifyReset() {
        final var tracker = new SequenceNumberTracker(32);

        Assertions.assertThat(tracker.verify(8)).isTrue();
        Assertions.assertThat(tracker.verify(9)).isTrue();
        Assertions.assertThat(tracker.verify(10)).isTrue();

        // Skipping 32 - 1 -> no reset
        Assertions.assertThat(tracker.verify(42)).isTrue();
        Assertions.assertThat(tracker.verify(43)).isFalse();
        Assertions.assertThat(tracker.verify(44)).isFalse();

        // Skipping 32 -> reset
        Assertions.assertThat(tracker.verify(78)).isTrue();
        Assertions.assertThat(tracker.verify(79)).isTrue();
        Assertions.assertThat(tracker.verify(80)).isTrue();
    }

    @Test
    void verifySizeTwo() {
        final var tracker = new SequenceNumberTracker(2);

        Assertions.assertThat(tracker.verify(0)).isTrue();
        Assertions.assertThat(tracker.verify(1)).isTrue();

        // skipping 1 -> no reset
        Assertions.assertThat(tracker.verify(3)).isTrue();
        Assertions.assertThat(tracker.verify(4)).isFalse();
        Assertions.assertThat(tracker.verify(5)).isTrue();

        // skipping 2 -> reset
        Assertions.assertThat(tracker.verify(8)).isTrue();
        Assertions.assertThat(tracker.verify(9)).isTrue();
        Assertions.assertThat(tracker.verify(10)).isTrue();
    }

    @Test
    void verifySizeOne() {
        final var tracker = new SequenceNumberTracker(1);

        Assertions.assertThat(tracker.verify(0)).isTrue();
        Assertions.assertThat(tracker.verify(1)).isTrue();

        Assertions.assertThat(tracker.verify(3)).isTrue();
        Assertions.assertThat(tracker.verify(4)).isTrue();

        Assertions.assertThat(tracker.verify(6)).isTrue();
    }

    @Test
    void verifyMultiRecordInOrder() {
        // IPFIX/NetFlow-v5: the sequence number counts records, so a stream of 10-record packets
        // advances the counter by 10 each time (0, 10, 20, ...). The in-between numbers are this
        // packet's own records, not gaps — none must be reported as missing.
        final var tracker = new SequenceNumberTracker(32);
        final int records = 10;
        for (long seq = 0; seq <= 500; seq += records) {
            Assertions.assertThat(tracker.verify(seq, records)).describedAs("seq=" + seq).isTrue();
        }
    }

    @Test
    void verifyMultiRecordDroppedPacketEventuallyReported() {
        // A genuinely dropped multi-record packet still leaves a gap that is reported once the
        // missing records age out of the patience window.
        final var tracker = new SequenceNumberTracker(32);
        final int records = 5;
        Assertions.assertThat(tracker.verify(0, records)).isTrue(); // records 0..4

        // Drop the packet that would carry records 5..9; resume at 10 and keep going.
        boolean reported = false;
        for (long seq = 10; seq <= 200; seq += records) {
            reported |= !tracker.verify(seq, records);
        }
        Assertions.assertThat(reported).describedAs("a dropped packet must eventually be reported").isTrue();
    }

    @Test
    void verifySizeZero() {
        final var tracker = new SequenceNumberTracker(0);

        Assertions.assertThat(tracker.verify(0)).isTrue();
        Assertions.assertThat(tracker.verify(1)).isTrue();

        Assertions.assertThat(tracker.verify(3)).isTrue();
        Assertions.assertThat(tracker.verify(4)).isTrue();

        Assertions.assertThat(tracker.verify(6)).isTrue();
    }
}
