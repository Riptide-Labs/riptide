/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;

import java.io.IOException;
import java.util.List;

/**
 * Persistence interface for flows.
 *
 * After parsing and processing of received flows, the result is passed to all exposed instances of this interface.
 */
public interface FlowRepository {

    /**
     * Persist a batch of flows.
     *
     * @param flows the flows which should be persisted
     *
     * @throws FlowException on any error happening during processing.
     */
    void persist(List<EnrichedFlow> flows) throws FlowException, IOException;

    /**
     * Keep a batch {@link #persist} refused, so the rows survive for an operator to inspect and
     * replay deliberately instead of being dropped (#548).
     *
     * <p><b>Nothing here ever re-inserts into {@code flows}, and nothing ever may.</b> A refused
     * insert is not always atomic ({@code MultiBlockPoisonProbeIT}), nobody can state which servers
     * are affected, and the 1-minute rollups are {@code SummingMergeTree} targets fed by
     * materialized views on {@code flows} — so a re-inserted row is <em>summed</em> into aggregates
     * whose retention deliberately outlives the raw table's. That inflation is silent, permanent,
     * and undetectable once the raw rows have expired. Replay is an operator's decision, taken with
     * that in front of them.
     *
     * <p><b>It does not rescue a batch lost to a severed transport.</b> This write goes to the same
     * server over the same client, so it fails too — #663 measured the two shapes a severed
     * transport takes and neither is reachable by a design that writes to ClickHouse. What it
     * addresses is the server that is reachable and refuses the batch.
     *
     * <p>The default throws, because "this repository has no dead-letter store" and "the dead-letter
     * table is not there" want the same handling from the caller: count the rows, log the cause
     * once, carry on. A caller must therefore treat a failure here as degraded rather than fatal —
     * see {@code BatchingFlowRepository.flush}.
     *
     * <p><b>Only {@code BatchingFlowRepository.flush} calls this</b>, so it is reached only while
     * {@code riptide.clickhouse.batch.enabled} is on (the default). That is deliberate — the
     * un-batched path keeps the synchronous rejection this exists to replace — and it is pinned by
     * {@code ClickhouseConfigurationTest.theUnbatchedPathDoesNotDeadLetterAndThatIsTheDecision}.
     *
     * @param flows the whole refused batch, one dead letter per flow so each carries its own tenant
     * @param cause what {@link #persist} threw, stored with the rows as the operator's only record
     *              of why the server would not take them
     * @throws FlowException if the batch could not be kept; the caller degrades, it never wedges
     */
    default void deadLetter(List<EnrichedFlow> flows, Throwable cause) throws FlowException, IOException {
        throw new FlowException(getClass().getSimpleName() + " has no dead-letter store");
    }

    default void start() {
    }

    default void stop() {
    }
}
