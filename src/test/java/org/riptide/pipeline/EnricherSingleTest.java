/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins how {@code Enricher.Single} turns a batch into one future: every flow visited in order with
 * the batch's source, a synchronous batch completes at once, and a pending, failed or thrown
 * per-flow result reaches the caller the way {@code Pipeline.process} relies on.
 */
public class EnricherSingleTest {

    private static final Source SOURCE = new Source("here", InetAddress.getLoopbackAddress());

    private static EnrichedFlow flow(final int port) {
        return EnrichedFlow.builder().srcPort(port).build();
    }

    private static List<EnrichedFlow> flows(final int count) {
        final List<EnrichedFlow> flows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            flows.add(flow(i));
        }
        return flows;
    }

    /** A {@code Single} whose per-flow result the test chooses, recording every visit. */
    private static final class Recording extends Enricher.Single {
        private final List<Source> sources = new ArrayList<>();
        private final List<EnrichedFlow> visited = new ArrayList<>();
        private final BiFunction<Integer, EnrichedFlow, CompletableFuture<Void>> results;

        Recording(final BiFunction<Integer, EnrichedFlow, CompletableFuture<Void>> results) {
            this.results = results;
        }

        static Recording synchronous() {
            return new Recording((i, flow) -> CompletableFuture.completedFuture(null));
        }

        @Override
        protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
            this.sources.add(source);
            this.visited.add(flow);
            return this.results.apply(this.visited.size() - 1, flow);
        }
    }

    @Test
    public void everyFlowIsVisitedInOrderWithTheSource() {
        final var flows = flows(3);
        final var enricher = Recording.synchronous();

        enricher.enrich(SOURCE, flows);

        assertThat(enricher.visited).containsExactlyElementsOf(flows);
        assertThat(enricher.sources).containsExactly(SOURCE, SOURCE, SOURCE);
    }

    @Test
    public void laterFlowsAreVisitedWhileAnEarlierOneIsPending() {
        final var flows = flows(3);
        final var enricher = new Recording((i, flow) -> i == 0
                ? new CompletableFuture<>()
                : CompletableFuture.completedFuture(null));

        enricher.enrich(SOURCE, flows);

        assertThat(enricher.visited).containsExactlyElementsOf(flows);
    }

    @Test
    public void synchronousBatchCompletesAtOnce() throws Exception {
        final var result = Recording.synchronous().enrich(SOURCE, flows(3));

        assertThat(result.isDone()).isTrue();
        assertThat(result.isCompletedExceptionally()).isFalse();
        assertThat(result.get()).isNull();
    }

    @Test
    public void emptyBatchCompletesWithoutVisiting() {
        final var enricher = Recording.synchronous();

        final var result = enricher.enrich(SOURCE, List.of());

        assertThat(result.isDone()).isTrue();
        assertThat(enricher.visited).isEmpty();
    }

    @Test
    public void onePendingFutureHoldsTheBatch() {
        final var pending = new CompletableFuture<Void>();
        final var enricher = new Recording((i, flow) -> i == 1
                ? pending
                : CompletableFuture.completedFuture(null));

        final var result = enricher.enrich(SOURCE, flows(3));

        assertThat(result.isDone()).isFalse();
        pending.complete(null);
        assertThat(result.isDone()).isTrue();
        assertThat(result.isCompletedExceptionally()).isFalse();
    }

    @Test
    public void twoPendingFuturesBothHoldTheBatch() {
        final var first = new CompletableFuture<Void>();
        final var third = new CompletableFuture<Void>();
        final var enricher = new Recording((i, flow) -> switch (i) {
            case 0 -> first;
            case 2 -> third;
            default -> CompletableFuture.completedFuture(null);
        });

        final var result = enricher.enrich(SOURCE, flows(3));

        first.complete(null);
        assertThat(result.isDone()).isFalse();
        third.complete(null);
        assertThat(result.isDone()).isTrue();
        assertThat(result.isCompletedExceptionally()).isFalse();
    }

    @Test
    public void oneFailedFutureFailsTheBatchWithItsCause() {
        final var cause = new IllegalStateException("rejected");
        final var enricher = new Recording((i, flow) -> i == 1
                ? CompletableFuture.failedFuture(cause)
                : CompletableFuture.completedFuture(null));

        final var result = enricher.enrich(SOURCE, flows(3));

        assertThat(result.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(result::get)
                .isInstanceOf(ExecutionException.class)
                .hasCause(cause);
    }

    @Test
    public void synchronousThrowEscapesTheBatch() {
        final var thrown = new IllegalArgumentException("bad flow");
        final var enricher = new Recording((i, flow) -> {
            if (i == 1) {
                throw thrown;
            }
            return CompletableFuture.completedFuture(null);
        });

        assertThatThrownBy(() -> enricher.enrich(SOURCE, flows(3))).isSameAs(thrown);
    }
}
