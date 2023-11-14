package org.riptide.pipeline;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public interface Enricher {

    CompletableFuture<Void> enrich(Source source, List<EnrichedFlow> flows);

    abstract class Streaming implements Enricher {
        public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
            return CompletableFuture.allOf(flows.stream()
                    .flatMap(flow -> this.enrich(source, flow))
                    .toArray(CompletableFuture[]::new));
        }

        protected abstract Stream<CompletableFuture<Void>> enrich(Source source, EnrichedFlow flow);
    }

    abstract class Single implements Enricher {
        public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
            return CompletableFuture.allOf(flows.stream()
                    .map(flow -> this.enrich(source, flow))
                    .toArray(CompletableFuture[]::new));
        }

        protected abstract CompletableFuture<Void> enrich(Source source, EnrichedFlow flow);
    }
}
