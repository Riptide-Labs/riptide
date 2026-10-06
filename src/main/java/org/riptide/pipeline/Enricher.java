/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public interface Enricher {

    CompletableFuture<Void> enrich(Source source, List<EnrichedFlow> flows);

    abstract class Streaming implements Enricher {
        @Override
        public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
            return CompletableFuture.allOf(flows.stream()
                    .flatMap(flow -> this.enrich(source, flow))
                    .toArray(CompletableFuture[]::new));
        }

        protected abstract Stream<CompletableFuture<Void>> enrich(Source source, EnrichedFlow flow);
    }

    abstract class Single implements Enricher {

        private static final CompletableFuture<Void> COMPLETED = CompletableFuture.completedFuture(null);

        /**
         * Visits every flow in order and keeps only the futures still worth waiting on: one that is
         * pending, or one that failed. Every {@code Single} shipped today completes synchronously, so
         * the common batch keeps nothing and returns {@link #done()} without a stream, a per-flow
         * future array or an {@code allOf} tree (#991). A failed future counts as kept on purpose:
         * dropping it would let {@code Pipeline.process} persist a batch an enricher rejected.
         */
        @Override
        public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
            List<CompletableFuture<Void>> pending = null;
            for (final var flow : flows) {
                final var result = this.enrich(source, flow);
                if (result.isDone() && !result.isCompletedExceptionally()) {
                    continue;
                }
                if (pending == null) {
                    pending = new ArrayList<>();
                }
                pending.add(result);
            }
            if (pending == null) {
                return COMPLETED;
            }
            if (pending.size() == 1) {
                return pending.getFirst();
            }
            return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
        }

        protected abstract CompletableFuture<Void> enrich(Source source, EnrichedFlow flow);

        /**
         * The future a synchronous enricher returns per flow. One shared instance: callers only read
         * it ({@code get}, {@code join}, {@code isDone}, composition), and never complete, cancel or
         * obtrude it.
         */
        protected static CompletableFuture<Void> done() {
            return COMPLETED;
        }
    }



    default void start() {
    }

    default void stop() {
    }
}
