/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a reading of the <em>instrument</em> into one that never goes backwards and never throws.
 *
 * <p>The e2e awaits poll two different kinds of source, and the distinction decides how a failed
 * read must be treated (#662). ClickHouse and riptide are the <b>subject</b>: if a read of them
 * fails, the run has learned something and should say so. nl6 is the <b>instrument</b> that drives
 * traffic at the subject, and a hiccup reading its status API is not evidence about ingest. Letting
 * one fail a wait is the false report #547 exists to remove, arriving one layer down.</p>
 *
 * <p>So an instrument read that fails contributes no information: the previous high-water mark is
 * returned and the wait continues. A genuinely broken instrument still fails the run, because the
 * reading stops advancing and {@code E2eTestSupport.awaitCount} stalls out on it. The cause is
 * logged on every failed read rather than swallowed, because the alternative to an opaque
 * {@code RuntimeException} must not be an opaque {@code Stalled at 0}.</p>
 *
 * <p>The high-water mark also keeps nl6's other quirk from reaching the helper at all:
 * {@code Nl6Container.sentRecords} answers {@code 0}, with no error, when the status reply lists no
 * collector for the protocol. A read that comes back lower than the mark is logged too, since the
 * decrease note {@code awaitCount} would otherwise have attached to its failure can no longer fire
 * for a source polled through here.</p>
 *
 * <p>An instance keeps one mark per protocol. The static {@link #advanced} is the step it takes, kept
 * separate so both the step and the keying are pinned by {@code LedgerReadingTest} under
 * {@code make jar} rather than only by an e2e run against a healthy nl6.</p>
 */
final class LedgerReading {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerReading.class);

    /** Highest reading seen per protocol. The awaits poll from one thread, so no locking. */
    private final Map<String, Long> highWater = new HashMap<>();

    /** A reading of the instrument, which may fail. */
    @FunctionalInterface
    interface Source {
        long read() throws Exception;
    }

    /**
     * The reading for {@code protocol} after one more attempt at {@code source}: monotonic, starting
     * at {@code 0}, and never throwing.
     */
    long advance(final String protocol, final Source source) {
        final long reading = advanced(highWater.getOrDefault(protocol, 0L), source,
                "sent_records for " + protocol);
        highWater.put(protocol, reading);
        return reading;
    }

    /**
     * The higher of {@code best} and a fresh reading, or {@code best} if the reading failed.
     *
     * @param best   the highest reading seen so far, and the floor of the result
     * @param source where the fresh reading comes from
     * @param what   names the reading in the log line a failure or a decrease produces
     */
    static long advanced(final long best, final Source source, final String what) {
        final long reading;
        try {
            reading = source.read();
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                // Not an instrument hiccup, so not logged as one. The wait's own Thread.sleep is
                // what turns the restored flag back into an exception.
                Thread.currentThread().interrupt();
                return best;
            }
            // Logged, not rethrown: see the class javadoc. A persistent failure still fails the run
            // by never advancing, and this is the line that says why.
            LOG.warn("Reading {} from the nl6 instrument failed; holding the last value {}. Cause: {}",
                    what, best, e.toString());
            return best;
        }
        if (reading < best) {
            LOG.warn("Reading {} from the nl6 instrument went backwards to {}; holding the last value {}",
                    what, reading, best);
            return best;
        }
        return reading;
    }
}
