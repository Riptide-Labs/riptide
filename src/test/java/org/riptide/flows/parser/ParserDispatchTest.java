/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.Identity;
import org.riptide.pipeline.Source;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatch seam's loss and lifecycle behaviour, modelled on {@code BatchingFlowRepositoryTest}:
 * saturate, assert the drop counter's exact tally, assert nothing grows without bound, and assert
 * shutdown accounts for what it could not drain.
 *
 * <p>Every case here also asserts the invariant that matters most and is invisible to a functional
 * test: <strong>the future returned by {@code transmit} is completed on every exit path.</strong>
 * {@code UdpListener} releases the packet's retained direct {@code ByteBuf} when it completes, so a
 * path that returns something pending leaks a buffer per packet — which is exactly how the 2 GB
 * direct pool was exhausted in about 90 seconds during development.
 */
class ParserDispatchTest {

    private static final InetSocketAddress REMOTE = new InetSocketAddress("10.0.0.1", 51000);
    private static final InetSocketAddress LOCAL = new InetSocketAddress("10.0.0.2", 4739);
    private static final int FLOWS_PER_PACKET = 3;

    private ScheduledExecutorService scheduler;
    private final List<StubParser> started = new ArrayList<>();

    @AfterEach
    void tearDown() {
        this.started.forEach(StubParser::stop);
        this.started.clear();
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
        }
    }

    @Test
    void udpDropsWhenSaturatedAndCountsEveryLostRecord() throws Exception {
        final var registry = new MetricRegistry();
        final var gate = new CountDownLatch(1);
        final var entered = new CountDownLatch(1);
        // One worker, one queue slot: the third packet has nowhere to go.
        final var tally = new AtomicInteger();
        final var parser = start(new StubParser("udp", registry, true, gated(entered, gate, tally)), 1, 1);

        final var accepted = new ArrayList<CompletableFuture<?>>();
        accepted.add(parser.dispatch());
        // Wait until the worker actually holds packet one and is parked, so the queue is provably
        // empty; otherwise packet two races the dequeue and may be the one that drops.
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("worker must pick up the first packet").isTrue();
        accepted.add(parser.dispatch());          // fills the single queue slot

        // The worker is parked on the gate and the single slot is taken, so this one must drop —
        // after the bounded offer wait, not before.
        final var dropped = parser.dispatch();

        assertThat(dropped)
                .as("a dropped packet must return a COMPLETED future or its ByteBuf leaks")
                .isCompleted();
        assertThat(counter(registry, "udp", "dispatchDrops"))
                .as("every lost record counted, not every lost packet")
                .isEqualTo(FLOWS_PER_PACKET);

        gate.countDown();
        for (final var f : accepted) {
            f.get(10, TimeUnit.SECONDS);
        }
        assertThat(tally.get()).isEqualTo(2 * FLOWS_PER_PACKET);
    }

    /**
     * The TCP case. On a reliable transport the exporter's bytes are already acknowledged, so
     * discarding them is silent data loss with no retransmission — and {@code TcpListener} does not
     * gate reads on the returned future, so blocking the submitter is the only back-pressure there
     * is. This asserts the base class does NOT drop.
     */
    @Test
    void reliableTransportBlocksRatherThanDropping() throws Exception {
        final var registry = new MetricRegistry();
        final var gate = new CountDownLatch(1);
        final var entered = new CountDownLatch(1);
        final var tally = new AtomicInteger();
        final var parser = start(new StubParser("tcp", registry, false, gated(entered, gate, tally)), 1, 1);

        parser.dispatch();
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("worker must pick up the first packet").isTrue();
        parser.dispatch();                        // fills the single queue slot

        // Third submission must block, not drop. Run it on a thread of its own rather than via
        // supplyAsync: the main thread is the only one that can open the gate so it must never be
        // the one that blocks here, and the common pool is a shared resource this test should not
        // depend on being free.
        final var blocked = new CompletableFuture<CompletableFuture<?>>();
        final var submitter = new Thread(() -> blocked.complete(parser.dispatch()), "blocked-submitter");
        submitter.setDaemon(true);
        submitter.start();
        Thread.sleep(300);
        assertThat(blocked.isDone()).as("submission must block while the queue is full").isFalse();
        assertThat(counter(registry, "tcp", "dispatchDrops"))
                .as("a reliable transport must never drop at this seam")
                .isZero();

        gate.countDown();
        blocked.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
        submitter.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(tally.get()).isEqualTo(3 * FLOWS_PER_PACKET);
    }

    @Test
    void shutdownAccountsForQueuedWorkItCouldNotDrain() throws Exception {
        final var registry = new MetricRegistry();
        final var gate = new CountDownLatch(1);
        final var entered = new CountDownLatch(1);
        final var parser = start(new StubParser("drain", registry, true,
                gated(entered, gate, new AtomicInteger())), 1, 4);

        parser.dispatch();                       // taken by the gated worker
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("worker must pick up the first packet").isTrue();
        final var queued = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < 3; i++) {
            queued.add(parser.dispatch());       // sits in the queue, will never run
        }

        parser.stop();                           // shutdown() → 5s grace → shutdownNow()
        this.started.remove(parser);

        assertThat(counter(registry, "drain", "dispatchDrops"))
                .as("records discarded by shutdownNow must be counted, not silently lost")
                .isEqualTo(3 * FLOWS_PER_PACKET);
        assertThat(queued)
                .as("and their futures completed, or the retained buffers leak on every restart")
                .allSatisfy(f -> assertThat(f).isCompleted());

        gate.countDown();
    }

    @Test
    void dispatchFailureDoesNotCountAsDispatched() throws Exception {
        final var registry = new MetricRegistry();
        final var parser = start(new StubParser("boom", registry, true, (source, flows) -> {
            throw new IllegalStateException("enricher exploded");
        }), 1, 4);

        parser.dispatch().get(10, TimeUnit.SECONDS);

        assertThat(meter(registry, "boom", "recordsScheduled")).isEqualTo(FLOWS_PER_PACKET);
        assertThat(meter(registry, "boom", "recordsDispatched"))
                .as("a failed dispatch must not read as delivered")
                .isZero();
    }

    @Test
    void sizingIsFixedOnceStarted() {
        final var parser = start(new StubParser("fixed", new MetricRegistry(), true, (s2, f2) -> { }), 2, 8);
        assertThat(parser.getQueueCapacity()).isEqualTo(8);
        // Silently ignoring this would leave the getter and the drop warning reporting a capacity
        // the queue does not have.
        assertThat(catchThrowableOf(() -> parser.setQueueCapacity(16)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(catchThrowableOf(() -> parser.setThreads(4)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The policy *wiring*, which the cases above cannot cover: they drive a stub that overrides
     * {@code mayDropOnFullQueue} directly, so flipping the real classes would not fail them. This
     * asserts the defaults that decide whether an exporter's acknowledged data can be discarded.
     */
    @Test
    void transportPolicyDefaultsBySuperclass() {
        final var registry = new MetricRegistry();
        final var udp = new UdpParserBase(Protocol.IPFIX, "wire-udp", (s2, f2) -> { },
                new Identity("t", "o", "z", "s"), registry) {
            @Override
            protected FlowPacket parse(final Session session, final io.netty.buffer.ByteBuf buffer) {
                return null;
            }

            @Override
            protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remote,
                                                                   final InetSocketAddress local) {
                return new Netflow9UdpParser.SessionKey(remote.getAddress(), local);
            }

            @Override
            public Object dumpInternalState() {
                return "wire-udp";
            }
        };
        final var reliable = new ParserBase(Protocol.IPFIX, "wire-tcp", (s2, f2) -> { },
                new Identity("t", "o", "z", "s"), registry) {
            @Override
            public Object dumpInternalState() {
                return "wire-tcp";
            }
        };

        assertThat(udp.mayDropOnFullQueue())
                .as("datagram transports may drop, counted, rather than push back into the kernel")
                .isTrue();
        assertThat(reliable.mayDropOnFullQueue())
                .as("the base default must be no-drop: IPFIX/TCP data is already acknowledged and "
                        + "there is no retransmission, so dropping it is silent loss")
                .isFalse();
    }

    @Test
    void queueCapacityIsRangeChecked() {
        final var parser = new StubParser("range", new MetricRegistry(), true, (s2, f2) -> { });
        assertThat(catchThrowableOf(() -> parser.setQueueCapacity(0)))
                .isInstanceOf(IllegalArgumentException.class);
        // ArrayBlockingQueue pre-allocates, so an absurd value must fail fast rather than OOM
        // inside Listener.start() during boot.
        assertThat(catchThrowableOf(() -> parser.setQueueCapacity(Integer.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A Set discarded for a missing Template must be counted. Before this counter the discard was a
     * {@code LOG.debug} in {@code ipfix/proto/Packet} and nothing else, so on a collector running at
     * {@code WARN} it was entirely invisible — records gone, every metric reading healthy.
     */
    @Test
    void undecodableSetsAreCounted() throws Exception {
        final var registry = new MetricRegistry();
        final var parser = start(new StubParser("undec", registry, true, (s2, f2) -> { }), 1, 16);

        parser.dispatch(FLOWS_PER_PACKET, 2).get(10, TimeUnit.SECONDS);

        assertThat(counter(registry, "undec", "undecodableSets"))
                .as("Sets, not records: without the Template the record count is unknowable")
                .isEqualTo(2);
    }

    /**
     * The placement guard. A packet whose <em>every</em> Data Set was undecodable builds no flows, so
     * {@code transmit} returns early — counting after that return would miss exactly the loss the
     * counter exists to expose, and would do so silently.
     */
    @Test
    void undecodableSetsAreCountedEvenWhenThePacketYieldsNoFlows() throws Exception {
        final var registry = new MetricRegistry();
        final var parser = start(new StubParser("undec-only", registry, true, (s2, f2) -> { }), 1, 16);

        final var future = parser.dispatch(0, 3);

        assertThat(counter(registry, "undec-only", "undecodableSets"))
                .as("counted before the empty-flow early return, or this loss stays invisible")
                .isEqualTo(3);
        assertThat(future)
                .as("a packet with nothing decodable must still complete its future or the buffer leaks")
                .isCompleted();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A dispatcher that announces arrival on {@code entered}, then parks on {@code gate} (if any)
     * and tallies what it received.
     *
     * <p>{@code entered} is what makes the saturation tests deterministic. {@code start()} calls
     * {@code prestartAllCoreThreads()}, so the worker already counts towards {@code corePoolSize}
     * and {@code execute()} routes the very first packet through {@code workQueue.offer()} rather
     * than handing it straight to a thread. Whether the <em>second</em> packet finds a free slot
     * therefore depends on whether the worker has dequeued the first one yet — a race the test must
     * not guess at. Waiting on {@code entered} pins the queue's occupancy before submitting more.
     */
    private static BiConsumer<Source, List<Flow>> gated(final CountDownLatch entered,
                                                        final CountDownLatch gate,
                                                        final AtomicInteger tally) {
        return (source, flows) -> {
            entered.countDown();
            if (gate != null) {
                try {
                    gate.await(20, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            tally.addAndGet(flows.size());
        };
    }

    private static Throwable catchThrowableOf(final Runnable r) {
        try {
            r.run();
            return null;
        } catch (final Throwable t) {
            return t;
        }
    }

    private StubParser start(final StubParser parser, final int threads, final int capacity) {
        parser.setThreads(threads);
        parser.setQueueCapacity(capacity);
        if (this.scheduler == null) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
        }
        parser.start(this.scheduler);
        this.started.add(parser);
        return parser;
    }

    private static long counter(final MetricRegistry r, final String parser, final String name) {
        final var c = r.getCounters().get(MetricRegistry.name("parsers", parser, name));
        assertThat(c).as("counter parsers.%s.%s", parser, name).isNotNull();
        return c.getCount();
    }

    private static long meter(final MetricRegistry r, final String parser, final String name) {
        final var m = r.getMeters().get(MetricRegistry.name("parsers", parser, name));
        assertThat(m).as("meter parsers.%s.%s", parser, name).isNotNull();
        return m.getCount();
    }

    /** A parser whose only job is to let a test drive {@code transmit}. */
    private static final class StubParser extends ParserBase {
        /** One mock reused for every record: transmit only counts them, it never reads a field. */
        private static final Flow FLOW = org.mockito.Mockito.mock(Flow.class);

        private final boolean mayDrop;
        private final Session session;

        StubParser(final String name,
                   final MetricRegistry registry,
                   final boolean mayDrop,
                   final BiConsumer<Source, List<Flow>> dispatcher) {
            super(Protocol.IPFIX, name, dispatcher, new Identity("t", "o", "z", "s"), registry);
            this.mayDrop = mayDrop;
            this.session = new UdpSessionManager(Duration.ofMinutes(30), () -> new SequenceNumberTracker(32))
                    .getSession(new Netflow9UdpParser.SessionKey(REMOTE.getAddress(), LOCAL));
        }

        @Override
        protected boolean mayDropOnFullQueue() {
            return this.mayDrop;
        }

        @Override
        public Object dumpInternalState() {
            return "stub";
        }

        CompletableFuture<?> dispatch() {
            return transmit(Instant.now(), packet(FLOWS_PER_PACKET, 0), this.session);
        }

        /** A packet carrying {@code flowCount} decodable records and {@code undecodable} skipped Sets. */
        CompletableFuture<?> dispatch(final int flowCount, final int undecodable) {
            return transmit(Instant.now(), packet(flowCount, undecodable), this.session);
        }

        private FlowPacket packet(final int flowCount, final int undecodable) {
            return new FlowPacket() {
                @Override
                public Stream<Flow> buildFlows(final Instant receivedAt) {
                    return Stream.generate(() -> FLOW).limit(flowCount);
                }

                @Override
                public long getObservationDomainId() {
                    return 0;
                }

                @Override
                public long getSequenceNumber() {
                    return 0;
                }

                @Override
                public int undecodableSets() {
                    return undecodable;
                }
            };
        }
    }
}
