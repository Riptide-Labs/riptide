/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.riptide.flows.parser.data.Flow;
import org.riptide.repository.FlowRepository;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * How a persist failure leaves the pipeline.
 *
 * <p>{@code FlowPersister.persist} declares both {@link FlowException} and {@link IOException}, and
 * the two used to leave here differently: a {@code FlowException} propagated to {@code Daemon}'s
 * dispatcher, which charges {@code pipeline.dispatchErrors} and names the exporter, while an
 * {@code IOException} was logged and {@code process} returned normally — so the caller saw a
 * success and the records were counted nowhere.</p>
 *
 * <p>No shipped delegate can currently reach that branch: {@code ClickhouseRepository} converts
 * both of its failure modes into {@code FlowException}, and {@code BatchingFlowRepository} throws
 * neither. These rows therefore pin a boundary rather than reproduce an outage — the interface
 * permits the divergence, so a third repository would reintroduce silent loss.</p>
 */
class PipelineTest {

    private final MetricRegistry metrics = new MetricRegistry();

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    /** Enough of a flow for the mapper to build an {@link EnrichedFlow}; the values do not matter. */
    private static Flow oneFlow() throws Exception {
        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcPort()).thenReturn(80);
        when(flow.getDstPort()).thenReturn(36592);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getProtocol()).thenReturn(6);
        return flow;
    }

    private Pipeline pipelineOver(final FlowRepository repository) {
        return new Pipeline(List.of(), new FlowPersister("under-test", repository, this.metrics),
                this.metrics, this.flowMapper);
    }

    @Test
    void anIoExceptionFromPersistLeavesAsAFlowException() throws Exception {
        final var pipeline = pipelineOver(new ThrowingRepository(new IOException("socket closed")));

        assertThatThrownBy(() -> pipeline.process(new Source("here", InetAddress.getLoopbackAddress()),
                List.of(oneFlow())))
                .as("an IOException must not be swallowed: Daemon's dispatcher is what counts the"
                        + " loss, and it only sees what escapes process()")
                .isInstanceOf(FlowException.class)
                .hasMessageContaining("under-test")
                .hasRootCauseInstanceOf(IOException.class);
    }

    @Test
    void aFlowExceptionFromPersistStillLeavesUnchanged() throws Exception {
        final var pipeline = pipelineOver(new ThrowingRepository(new FlowException("rejected")));

        assertThatThrownBy(() -> pipeline.process(new Source("here", InetAddress.getLoopbackAddress()),
                List.of(oneFlow())))
                .as("the path that already reached the counter is untouched: the delegate's own"
                        + " exception must arrive intact, not re-wrapped into a second one whose"
                        + " message loses what the delegate said")
                .isInstanceOf(FlowException.class)
                .hasMessage("rejected");
    }

    @Test
    void aSuccessfulPersistStillReturnsNormally() throws Exception {
        final var repository = new RecordingRepository();

        pipelineOver(repository).process(new Source("here", InetAddress.getLoopbackAddress()),
                List.of(oneFlow()));

        assertThat(repository.persisted)
                .as("the happy path is not disturbed by the rethrow")
                .isEqualTo(1);
    }

    /** Fails every insert with a given exception, so the escape route can be observed. */
    private record ThrowingRepository(Exception failure) implements FlowRepository {
        @Override
        public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
            switch (this.failure) {
                case IOException io -> throw io;
                case FlowException flow -> throw flow;
                default -> throw new IllegalStateException("unsupported failure", this.failure);
            }
        }
    }

    /** Counts what reached persistence. */
    private static final class RecordingRepository implements FlowRepository {
        private int persisted;

        @Override
        public void persist(final List<EnrichedFlow> flows) {
            this.persisted += flows.size();
        }
    }
}
