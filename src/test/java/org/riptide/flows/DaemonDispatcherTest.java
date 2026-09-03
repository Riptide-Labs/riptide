/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.pipeline.FlowPersister;
import org.riptide.pipeline.Pipeline;
import org.riptide.repository.FlowRepository;
import org.riptide.pipeline.Source;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The dispatcher {@link Daemon} hands every parser, driven directly rather than imitated.
 *
 * <p>This exists because imitating it is what went wrong. {@code ParserDispatchTest} asserted that
 * a failed dispatch does not count as dispatched, and passed — but only because <em>its</em> stub
 * dispatcher rethrew. The real one catches, counts, and returns normally, so {@code ParserBase}
 * marks the records dispatched and the property that test named never held in production: five
 * weeks between #391 adding it and #723 noticing. A stub shaped like the real thing would have been
 * the same mistake one step further along, so these cases call {@link Daemon#dispatcherFor} over a
 * real {@link Pipeline}.</p>
 *
 * <p>What they pin is the contract {@code docs/docs/deploy/operations.md} documents:
 * {@code recordsDispatched} does not exclude {@code dispatchErrors}, and delivery is
 * {@code recordsReceived − dispatchDrops − dispatchErrors}. If the dispatcher is ever changed to
 * rethrow, or to catch an {@link Error}, these fail — which is the point, because either change
 * would also make that documented arithmetic wrong, and the guide is a sibling nobody would
 * otherwise revisit.</p>
 */
class DaemonDispatcherTest {

    private final MetricRegistry metrics = new MetricRegistry();
    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    private static Source source() throws Exception {
        return new Source("here", InetAddress.getByName("10.0.0.1"));
    }

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

    private long dispatchErrors() {
        return this.metrics.counter(MetricRegistry.name("pipeline", "dispatchErrors")).getCount();
    }

    /**
     * The load-bearing fact, and the one the old test denied: the dispatcher does not rethrow.
     *
     * <p>Asserted as "does not throw" rather than inferred from a counter, because it is the
     * returning-normally that makes {@code ParserBase} mark the records dispatched. A dispatcher
     * that counted the error and <em>then</em> rethrew would satisfy a counter-only assertion and
     * still change what every delivery gauge means.</p>
     */
    @Test
    void aFailedDispatchIsCountedAndSwallowedRatherThanRethrown() throws Exception {
        final var dispatcher = Daemon.dispatcherFor(
                pipelineOver(new ThrowingRepository(new FlowException("rejected"))), this.metrics);
        final List<Flow> flows = List.of(oneFlow(), oneFlow(), oneFlow());

        assertThatCode(() -> dispatcher.accept(source(), flows))
                .as("returning normally is what makes ParserBase mark these records dispatched")
                .doesNotThrowAnyException();
        assertThat(dispatchErrors())
                .as("the whole packet is charged, since a packet's records dispatch as one batch")
                .isEqualTo(flows.size());
    }

    /**
     * A {@code RuntimeException} is caught too, not only {@link FlowException}. A shut-down SNMP
     * pool throws {@code RejectedExecutionException} and any enricher can NPE; those used to escape
     * into the dispatch task and be logged with no count and no exporter.
     *
     * <p>The failure has to be one that reaches the dispatcher <em>unwrapped</em>, and most do not:
     * {@code Pipeline.process} catches {@code Exception} around enrichment and {@code IOException}
     * around persistence, turning both into {@link FlowException}. So an {@code IOException}
     * fixture — the obvious choice, and this test's first one — exercises the {@code FlowException}
     * arm and says nothing about this one. Narrowing the catch to {@code FlowException} alone left
     * it green. {@code RejectedExecutionException} from {@code persist} is outside both of those
     * catches, which is why it is the fixture here.</p>
     */
    @Test
    void aRuntimeExceptionIsCountedTheSameWay() throws Exception {
        final var dispatcher = Daemon.dispatcherFor(
                pipelineOver(new ThrowingRepository(new RejectedExecutionException("pool shut down"))),
                this.metrics);

        assertThatCode(() -> dispatcher.accept(source(), List.of(oneFlow())))
                .doesNotThrowAnyException();
        assertThat(dispatchErrors()).isEqualTo(1);
    }

    /** The {@link FlowException} arm, which is what a persist-path {@code IOException} becomes. */
    @Test
    void aWrappedIoFailureIsCountedAsAFlowException() throws Exception {
        final var dispatcher = Daemon.dispatcherFor(
                pipelineOver(new ThrowingRepository(new IOException("socket closed"))), this.metrics);

        assertThatCode(() -> dispatcher.accept(source(), List.of(oneFlow())))
                .doesNotThrowAnyException();
        assertThat(dispatchErrors()).isEqualTo(1);
    }

    /**
     * Every failure is charged, not just the ones that get logged.
     *
     * <p>The warning is rate-limited to one per 10s, so on a persistently failing enricher almost
     * every packet takes the branch where {@code tryAcquire} returns false. Counting inside that
     * branch would undercount the loss by whatever the failure rate is — and a single-dispatch test
     * cannot see it, because the limiter always permits the first. Two failures through <em>one</em>
     * dispatcher is the smallest fixture that can.</p>
     */
    @Test
    void aFailureIsChargedEvenWhenItsWarningIsRateLimited() throws Exception {
        final var dispatcher = Daemon.dispatcherFor(
                pipelineOver(new ThrowingRepository(new FlowException("rejected"))), this.metrics);
        final List<Flow> flows = List.of(oneFlow(), oneFlow());

        dispatcher.accept(source(), flows);
        dispatcher.accept(source(), flows);

        assertThat(dispatchErrors())
                .as("the second failure is silent but must still be counted")
                .isEqualTo(2L * flows.size());
    }

    /**
     * An {@link Error} is <em>not</em> caught, and that is load-bearing rather than incidental.
     *
     * <p>{@code ParserBase}'s comment says "Only an `Error` skips the mark", and the operations
     * guide's delivery arithmetic depends on it. But that invariant was asserted only against a
     * hand-written stub in {@code ParserDispatchTest}: widening this catch to {@code Throwable}
     * would leave every one of those cases green while silently making {@code recordsDispatched}
     * count records that a fatal error lost. This is the case that notices.</p>
     */
    @Test
    void anErrorIsNotCaught() throws Exception {
        final var dispatcher = Daemon.dispatcherFor(
                pipelineOver(new ThrowingRepository(new DispatchProbeError())), this.metrics);

        assertThatThrownBy(() -> dispatcher.accept(source(), List.of(oneFlow())))
                .as("an Error must propagate so ParserBase skips the mark")
                .isInstanceOf(DispatchProbeError.class);
        assertThat(dispatchErrors())
                .as("and nothing is charged, since the dispatcher never reached its catch")
                .isZero();
    }

    /**
     * A purpose-named {@link Error}, rather than a synthetic {@code StackOverflowError}: the latter
     * is a {@code VirtualMachineError} and appears in captured output as something an operator
     * would triage as real.
     */
    private static final class DispatchProbeError extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A successful dispatch charges nothing, so the counter above is not simply always rising. */
    @Test
    void aSuccessfulDispatchChargesNothing() throws Exception {
        final var dispatcher = Daemon.dispatcherFor(pipelineOver(flows -> { }), this.metrics);

        dispatcher.accept(source(), List.of(oneFlow(), oneFlow()));

        assertThat(dispatchErrors()).isZero();
    }

    /**
     * Two receivers share one {@code pipeline.dispatchErrors}, which is what makes the counts
     * comparable across them — and what would break if the counter were ever registered per parser.
     */
    @Test
    void everyDispatcherOverOneRegistrySharesTheErrorCounter() throws Exception {
        final var first = Daemon.dispatcherFor(
                pipelineOver(new ThrowingRepository(new FlowException("a"))), this.metrics);
        final var second = Daemon.dispatcherFor(
                pipelineOver(new ThrowingRepository(new FlowException("b"))), this.metrics);

        first.accept(source(), List.of(oneFlow()));
        second.accept(source(), List.of(oneFlow()));

        assertThat(dispatchErrors())
                .as("one counter for the collector, not one per receiver")
                .isEqualTo(2);
    }

    /** Fails every insert with a given exception, so the escape route can be observed. */
    private record ThrowingRepository(Throwable failure) implements FlowRepository {
        @Override
        public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
            switch (this.failure) {
                case IOException io -> throw io;
                case FlowException flow -> throw flow;
                // Thrown as itself, not wrapped: a RuntimeException from persist is outside every
                // catch in Pipeline.process, which is the only way one reaches the dispatcher
                // unwrapped and therefore the only way that arm can be exercised.
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                case null -> throw new IllegalStateException("no failure configured");
                default -> throw new IllegalStateException("unsupported failure", this.failure);
            }
        }
    }
}
