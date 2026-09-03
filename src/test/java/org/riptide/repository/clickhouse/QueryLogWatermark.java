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

/**
 * Makes {@code system.query_log} current before a test reads it (#737).
 *
 * <p>{@code SYSTEM FLUSH LOGS} moves what is already queued. A query's entry is queued when the
 * query <em>finishes</em>, so a flush issued the moment after the work completed can miss it, and
 * the read that follows sees nothing. One flush is therefore not a synchronisation point; it is a
 * race that the reader loses at some rate.</p>
 *
 * <p>The wait is on a sentinel of our own rather than on the row the caller wants, because two of
 * the three call sites want a row and the third asserts an <em>absence</em>. Polling until the
 * expected row appears cannot serve the third: absence is the expected answer there, so a retry
 * loop would either burn the whole timeout on every green run or be written to stop at the first
 * read and change nothing. A sentinel query issued after the work settles both — the log queue is
 * ordered, so once the sentinel's own entry is visible every entry queued before it is too, and
 * the log is current for everything the caller cares about, present or absent.</p>
 */
final class QueryLogWatermark {

    /**
     * Generous, because it only ever elapses when the premise is wrong. {@code SYSTEM FLUSH LOGS}
     * is synchronous, so a sentinel that has finished is normally visible on the first or second
     * poll; this bound exists to fail with a message instead of hanging, and lengthening it is not
     * a fix for anything.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(100);

    private QueryLogWatermark() {
    }

    /**
     * Blocks until {@code system.query_log} holds an entry for every query that had already
     * finished when this was called.
     *
     * @param client the connection to flush and read through; the sentinel is issued on it
     * @throws AssertionError if the log has not caught up within {@link #TIMEOUT}
     */
    static void awaitCurrent(final Client client) throws Exception {
        // The query id, not the query text: the poll below has to name what it is looking for, and
        // anything it can name in the text it also contains, which would make it match itself.
        final var sentinel = "riptide-query-log-watermark-" + UUID.randomUUID();
        client.queryAll("SELECT 1 AS sentinel", new QuerySettings().setQueryId(sentinel));

        final var deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            client.execute("SYSTEM FLUSH LOGS").get();
            if (logged(client, sentinel)) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("system.query_log has not caught up: no QueryFinish entry"
                        + " for sentinel query_id " + sentinel + " after " + TIMEOUT.toSeconds()
                        + "s of SYSTEM FLUSH LOGS, so any read of it now would be reading a log"
                        + " that is missing entries for queries that have already completed");
            }
            Thread.sleep(POLL.toMillis());
        }
    }

    private static boolean logged(final Client client, final String queryId) throws Exception {
        try (var records = client.queryRecords("SELECT count() AS c FROM system.query_log"
                + " WHERE type = 'QueryFinish' AND query_id = '" + queryId + "'").get()) {
            for (final var record : records) {
                return record.getLong("c") > 0;
            }
        }
        return false;
    }
}
