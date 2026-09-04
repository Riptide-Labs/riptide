/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Narrows the window in which a read of {@code system.query_log} can miss an entry (#737).
 *
 * <p>{@code SYSTEM FLUSH LOGS} moves what is already queued. A query's entry is queued when the
 * query <em>finishes</em>, so a flush issued the moment after the client observed the work complete
 * can miss it, and the read that follows sees nothing. One flush is therefore not a synchronisation
 * point; it is a race that the reader loses at some rate.</p>
 *
 * <p><b>What this does and does not establish.</b> It issues a sentinel query of its own and waits
 * until the sentinel's entry is visible. That is a substantial narrowing and not a proof: the same
 * premise that makes one flush racy — that an entry is enqueued some time after the client observes
 * completion — applies to the sentinel too, and at all three call sites the sentinel is issued on a
 * different connection from the one that did the work ({@code admin} or {@code queryClient} against
 * {@code ProvisioningCommand}'s and {@code ClickhouseRepository}'s own clients). Nothing here
 * measures that cross-connection ordering. The assumption is that the lag between completion and
 * enqueue is roughly uniform, so a sentinel issued after the caller observed its work finish is
 * enqueued after that work's entry; under it, a visible sentinel means the log is current for
 * everything the caller cares about. Observed to hold on ClickHouse 26.7.3.19, the pinned image.</p>
 *
 * <p>The wait is on a sentinel rather than on the row the caller wants because two of the three
 * call sites want a row and the third asserts an <em>absence</em>. Polling until the expected row
 * appears cannot serve the third: absence is the expected answer there, so a retry loop would
 * either burn the whole timeout on every green run or be written to stop at the first read and
 * change nothing.</p>
 */
final class QueryLogWatermark {

    /**
     * Generous, because it only ever elapses when the assumption above does not hold.
     * {@code SYSTEM FLUSH LOGS} is synchronous, so a sentinel that has finished is normally visible
     * on the first or second poll. This bound exists to fail with a message instead of hanging, and
     * lengthening it is not a fix for anything.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(100);

    private QueryLogWatermark() {
    }

    /**
     * Issues a sentinel query and blocks until {@code system.query_log} holds its entry.
     *
     * <p>See the class javadoc for what that does and does not say about entries logged before it.
     * Pinned by {@code ClickhouseRepositoryIT.awaitCurrentMakesAFinishedQueryVisible}, which drives
     * this from a second connection.</p>
     *
     * @param client the connection to flush and read through; the sentinel is issued on it
     * @throws AssertionError if the sentinel is not visible, or any call it makes has not returned,
     *         within {@link #TIMEOUT}
     */
    static void awaitCurrent(final Client client) throws Exception {
        // The query id, not the query text: the poll below has to name what it is looking for, and
        // anything it can name in the text it also contains, which would make it match itself.
        final var sentinel = "riptide-query-log-watermark-" + UUID.randomUUID();
        final var deadline = Instant.now().plus(TIMEOUT);
        try (var ignored = within(
                client.queryRecords("SELECT 1 AS sentinel", new QuerySettings().setQueryId(sentinel)),
                deadline, sentinel, "the sentinel query")) {
            // Closed rather than read: the row is not the point, the log entry it leaves is.
        }

        while (true) {
            within(client.execute("SYSTEM FLUSH LOGS"), deadline, sentinel, "SYSTEM FLUSH LOGS");
            if (logged(client, sentinel, deadline)) {
                return;
            }
            if (!Instant.now().isBefore(deadline)) {
                throw expired(sentinel, "no QueryFinish entry appeared for it, so any read of the"
                        + " log now would be missing entries for queries that have already completed");
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private static boolean logged(final Client client, final String queryId, final Instant deadline)
            throws Exception {
        try (var records = within(client.queryRecords("SELECT count() AS c FROM system.query_log"
                + " WHERE type = 'QueryFinish' AND query_id = '" + queryId + "'"),
                deadline, queryId, "the query_log poll")) {
            for (final var record : records) {
                return record.getLong("c") > 0;
            }
        }
        return false;
    }

    /**
     * Waits on {@code pending} with whatever is left of the budget.
     *
     * <p>Every call this helper makes goes through here. An untimed {@code get} would hang the whole
     * suite on one stalled server call while {@link #TIMEOUT} — checked only between iterations —
     * says in its own javadoc that it exists to prevent exactly that.</p>
     */
    private static <T> T within(final CompletableFuture<T> pending, final Instant deadline,
            final String sentinel, final String what) throws Exception {
        final long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) {
            pending.cancel(true);
            throw expired(sentinel, what + " had no budget left to run in");
        }
        try {
            return pending.get(remaining, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            pending.cancel(true);
            throw expired(sentinel, what + " did not return");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Names the sentinel and what was being waited for, never a bare timeout. */
    private static AssertionError expired(final String sentinel, final String what) {
        return new AssertionError("system.query_log has not caught up after " + TIMEOUT.toSeconds()
                + "s: waiting on sentinel query_id " + sentinel + ", " + what);
    }
}
