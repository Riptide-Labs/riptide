/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks.pipeline;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * What one batch through {@code Enricher.Single.enrich(Source, List)} costs when every per-flow
 * result is already complete, as it is for all five {@code Single} enrichers. The per-flow method
 * does nothing but return a fresh {@code completedFuture(null)}, the shape every enricher had
 * before #991, so the same source runs on both arms of an A/B. That per-flow future is part of the
 * figure only while the batch method stores it: once the loop stops keeping completed futures, the
 * JIT scalar-replaces it and B/op reads about zero. 19 flows is the flow-knee lab's batch size
 * (18.7 flows per packet at 11,000 devices). Run with {@code -prof gc} for B/op.
 */
@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class EnricherBatchBenchmark {

    @Param({"1", "19", "100"})
    public int flows;

    private Source source;
    private List<EnrichedFlow> batch;
    private Enricher enricher;

    @Setup
    public void setup() throws Exception {
        this.source = new Source("bench", InetAddress.getByName("192.0.2.1"));
        this.batch = new ArrayList<>(this.flows);
        for (int i = 0; i < this.flows; i++) {
            this.batch.add(EnrichedFlow.builder()
                    .srcAddr(InetAddress.getByName("10.0.0." + (i % 256)))
                    .dstAddr(InetAddress.getByName("198.51.100.1"))
                    .build());
        }
        this.enricher = new NoopEnricher();
    }

    @Benchmark
    public Object batch() {
        return this.enricher.enrich(this.source, this.batch).join();
    }

    private static final class NoopEnricher extends Enricher.Single {
        @Override
        protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
