/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import java.util.Arrays;

/**
 * Tracks sequence numbers and verify completeness.
 *
 * Sequence numbers passed in to the tracker using the {@link #verify(long)} method are checked for completeness. I.e.
 * every sequence number has been passed in exactly once.
 *
 * While checking for completeness, the tracker allows for sequence numbers to be passed in out of order. Therefore it
 * keeps track of a short history. The {@code patience} parameter specifies the length of this history controlling how
 * much the expected sequence number can advance before the missing sequence number is reported as missing. If the
 * missing sequence number is passed in before the the expected sequence number advances to much, the element is
 * considered out-of-order but not missing and history is updated.
 *
 * To allow re-initialisation of sequence numbers, the tracker is lenient for huge sequence number jumps. If the passed
 * in sequence number differs from the expected sequence number by more than what {@code patience} parameters allows,
 * the tracker is reset and the element is considered valid.
 */
public class SequenceNumberTracker {

    /**
     * The highest seen sequence number.
     */
    private long current;

    /**
     * The history of seen sequence numbers relative to the current sequence number.
     *
     * The history is stored in a ring with the size of the expected {@code patience}. Therefore a sequence number
     * {@code q} and {@code q - patience} will share the same slot in the ring. While marking {@code q} as seen (or
     * missing) the history will return the status of {@code q - patience}. As the current sequence number is only
     * driven forwards, this concludes that {@code q - patience} has exceeded patience in just that moment.
     */
    private final Ring seen;

    public SequenceNumberTracker(final int patience) {
        if (patience < 0) {
            throw new IllegalArgumentException("patience must be positive");
        }

        this.seen = patience > 1
            ? new Ring(patience)
            : null;

        // Set to minimal value to trigger re-initialisation on first sequence number passed
        this.current = Integer.MIN_VALUE;
    }

    /** Verify a packet that advances the sequence counter by a single unit (a packet/datagram
     *  counter, as in NetFlow v9 and sFlow). */
    public boolean verify(final long sequenceNumber) {
        return verify(sequenceNumber, 1);
    }

    /**
     * Verify a packet whose header sequence number is {@code sequenceNumber} and which carries
     * {@code count} sequence units. For IPFIX and NetFlow v5 the sequence number counts records/flows,
     * so a packet with N records advances the counter by N and covers the range
     * {@code [sequenceNumber, sequenceNumber + count)} — all present. For NetFlow v9 / sFlow the counter
     * is a packet/datagram count and {@code count} is 1, in which case this reduces exactly to the
     * single-unit behaviour.
     */
    public synchronized boolean verify(final long sequenceNumber, final int count) {
        // Fast-path for disabled sequence tracking - everything is valid
        if (this.seen == null) {
            return true;
        }

        final int size = this.seen.size();
        final long last = sequenceNumber + Math.max(count, 1) - 1; // inclusive last record in the packet

        // Detect jumps and reinitialize
        if (Math.abs(this.current - sequenceNumber) > size) {
            this.current = last;

            // Start over with a history where everything is marked as seen
            this.seen.reset(true);
            return true;
        }

        // Check if input is out of order: the whole packet precedes the current position. Mark its
        // records as seen (filling any pending-missing slots within the window).
        if (last < this.current) {
            for (long x = Math.max(sequenceNumber, last - size + 1); x <= last; x++) {
                this.seen.set(x, true);
            }
            return true;
        }

        boolean valid = true;

        // Mark everything between the current position and this packet's start as missing (a real gap)
        for (long x = this.current + 1; x < sequenceNumber; x++) {
            valid &= this.seen.set(x, false);
        }

        // Mark this packet's records as seen. Only the last `size` fit the window; earlier records of
        // an oversized packet are present too but fall outside the out-of-order history.
        for (long x = Math.max(sequenceNumber, last - size + 1); x <= last; x++) {
            valid &= this.seen.set(x, true);
        }

        // Advance to the last record of this packet
        this.current = last;

        return valid;
    }

    /**
     * Number of elements that could be out of order.
     */
    public int getPatience() {
        return this.seen.size();
    }

    /**
     * A ring buffer where the elements are stored at {@code index % size}.
     */
    private static final class Ring {
        private final boolean[] values;

        private Ring(final int size) {
            if (size < 0) {
                throw new IllegalArgumentException("ring size must be >= 1");
            }
            this.values = new boolean[size];
        }

        public void reset(final boolean value) {
            Arrays.fill(this.values, value);
        }

        public boolean set(final long index, final boolean value) {
            // Calculate the index in the ring
            // This cast is safe because long mod int is always int
            final int wrapped = (int) (index % this.values.length);

            final boolean prev = this.values[wrapped];
            this.values[wrapped] = value;

            return prev;
        }

        public int size() {
            return this.values.length;
        }
    }
}
