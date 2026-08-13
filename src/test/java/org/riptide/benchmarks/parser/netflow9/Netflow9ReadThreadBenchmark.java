/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks.parser.netflow9;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ie.values.visitor.BooleanVisitor;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InetAddressVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TransactionalSession;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.ExporterIdentity;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * What one UDP event-loop thread pays per NetFlow v9 datagram, measured for issue #450.
 *
 * <p>#450 asks whether UDP ingest is capped by reading on a single event loop. The premise there is
 * that the thread "drains a socket into a handoff queue", which the code does not support: the
 * handoff to the parser pool happens <em>after</em> the full decode. Everything this benchmark
 * measures runs on {@code udp-listener-nio-<name>-0} before {@code ParserBase.transmit} enqueues
 * anything, so {@code 1s / decodeAndBuildFlows} is the theoretical single-thread packet ceiling for
 * this exporter's packet shape, with no kernel, no socket and no downstream in the way.
 *
 * <p><strong>Read it as an upper bound, not a prediction.</strong> The real loop additionally pays a
 * {@code recvfrom} syscall, two {@code Meter} marks, the {@code CompletableFuture} plumbing and the
 * enqueue, and it competes for cache with whatever else the JVM is doing. It also runs against many
 * exporters rather than one warm session. If the measured ceiling is already close to the offered
 * load, the single reader is a real constraint; if it is far above, #450's remedies (SO_REUSEPORT
 * fan-out, moving decode off the read thread) are solving a problem that is not there.
 *
 * <h2>Why the work is reconstructed rather than called through the parser</h2>
 *
 * <p>{@code UdpParserBase.parse} cannot be measured directly: it ends in
 * {@code ParserBase.transmit}, which hands off to the dispatch pool and returns a future, so timing
 * it would measure the enqueue and not the decode. The sequence below mirrors
 * {@code UdpParserBase.parse} (session lookup, transactional wrapper, decode) followed by the
 * pre-handoff half of {@code ParserBase.transmit} (sequence verification, {@code buildFlows}), and
 * stops where {@code enqueue} begins. <strong>If that path changes, this benchmark drifts</strong>,
 * and the drift is silent.
 *
 * <h2>Steady state, not cold start</h2>
 *
 * <p>The template is installed once in {@link #setup()} and the benchmark decodes a data-only packet
 * against a warm session. That is the steady state worth measuring: an exporter re-announces
 * templates on the order of minutes and sends data continuously, so template handling is a rounding
 * error in the mix. It also means the {@link TransactionalSession} undo stack stays empty here,
 * which is representative but does understate the cost of a template-carrying packet.
 *
 * <h2>The buffer must be pooled and direct</h2>
 *
 * <p>{@code UdpListener} never sets {@link io.netty.channel.ChannelOption#ALLOCATOR}, so datagram
 * content comes from {@link ByteBufAllocator#DEFAULT}: pooled, and direct on every platform we ship
 * on. The handler retains exactly that buffer, so every {@code BufferUtils.slice}/{@code uint16}/
 * {@code uint32} in the decode below reads through a direct {@code ByteBuf}. An earlier revision of
 * this benchmark used {@code Unpooled.wrappedBuffer(byte[])} and measured the heap implementation
 * instead, which understated {@code decodeOnly} by about 31% (12.0us against 15.8us) and so
 * overstated the packets/s ceiling by about a quarter. Since the whole point of the number is to
 * decide whether the single reader is a constraint, a bias in the direction that makes it look safer
 * is the one bias that must not be there. Do not switch this back to a heap buffer.
 *
 * <p>Fixture is a Cisco ASR9k data packet: a representative carrier-router packet shape. Note it
 * carries a single observation domain, so nothing here exercises the multi-domain template index or
 * the per-domain resolver costs. Per-packet record count is printed by {@link #setup()} so the ns/op
 * figure can be converted to records/s.
 */
@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
// Scope.Thread, not Scope.Benchmark: the state below is mutable (the buffer's reader index, the
// sequence counter) and sharing it across threads corrupts both. Since `make bench-jmh` advertises
// arbitrary BENCH_OPTS, -t is a flag someone investigating a threading question will reach for, and
// at Scope.Benchmark it produces interleaved garbage rather than a refusal.
@State(Scope.Thread)
public class Netflow9ReadThreadBenchmark {

    private static final String FOLDER = "/flows/";

    /** Matches {@code UdpParserBase}'s default, so housekeeping never expires the warm template. */
    private static final Duration TEMPLATE_TIMEOUT = Duration.ofMinutes(30);

    /** Matches {@code ParserBase}'s default patience. */
    private static final int SEQUENCE_PATIENCE = 32;

    private final ValueConversionService converter = new ValueConversionService(Netflow9RawFlow.class, List.of(
            new StringVisitor(),
            new BooleanVisitor(),
            new DoubleVisitor(),
            new DurationVisitor(),
            new InetAddressVisitor(),
            new InstantVisitor(),
            new IntegerVisitor(),
            new LongVisitor(),
            new UnsignedLongVisitor()
    ));

    private final Netflow9FlowBuilder flowBuilder = new Netflow9FlowBuilder(converter);

    private UdpSessionManager sessionManager;
    private UdpSessionManager.SessionKey sessionKey;
    private ByteBuf data;
    private long sequenceNumber = 1;

    @Setup
    public void setup() throws Exception {
        // The session key the listener would build from the datagram envelope: for NetFlow v9 the
        // remote port is deliberately excluded (exporters hop source ports), so this is exactly
        // what Netflow9UdpParser.buildSessionKey produces.
        final InetAddress remote = InetAddress.getLoopbackAddress();
        final InetSocketAddress local = new InetSocketAddress(InetAddress.getLoopbackAddress(), 9995);
        this.sessionKey = new Netflow9UdpParser.HostSessionKey(remote, local);

        this.sessionManager = new UdpSessionManager(TEMPLATE_TIMEOUT,
                () -> new SequenceNumberTracker(SEQUENCE_PATIENCE));

        // Install the template once, exactly as a template datagram would, so the measured packet
        // decodes against a warm session.
        final ByteBuf template = buffer("netflow9_test_cisco_asr9k_tpl260.dat");
        try {
            decode(template);
        } finally {
            template.release();
        }

        this.data = buffer("netflow9_test_cisco_asr9k_data260.dat");

        // Read the size before decoding, which consumes the buffer.
        final int bytes = this.data.readableBytes();
        final int records = decode(this.data).size();

        // Asserted, not just printed. If the template ever fails to install (a fixture swap, a
        // changed template ID), Packet takes the MissingTemplateException branch, counts an
        // undecodable set and skips it — and both benchmarks then measure the much cheaper
        // skip-the-set path while still reporting a perfectly plausible ns/op. With -f 2 and JSON
        // output the one line that would betray it scrolls past in stdout.
        if (records == 0) {
            throw new IllegalStateException(
                    "Fixture decoded to zero records: template 260 is not installed, so this would "
                            + "benchmark the undecodable-set path instead of the decode path");
        }

        System.out.printf("%n[setup] records/packet = %d, datagram bytes = %d%n", records, bytes);
    }

    @TearDown
    public void tearDown() {
        if (this.data != null) {
            this.data.release();
            this.data = null;
        }
    }

    /**
     * The decode half only: header framing, set walking, template resolution, record extraction.
     * Isolated from {@link #decodeAndBuildFlows} so the per-packet cost can be attributed. If the
     * cost is concentrated here, cheaper reads (recvmmsg) or more readers (SO_REUSEPORT) are the
     * lever; if it is concentrated in the difference, moving flow construction off the read thread
     * is.
     */
    @Benchmark
    public void decodeOnly(final Blackhole blackhole) throws Exception {
        // Rewound in the method rather than via @Setup(Level.Invocation): JMH documents that level
        // as unreliable below ~1ms/op, and this op is ~20us. Both benchmarks pay it equally.
        this.data.resetReaderIndex();

        final TransactionalSession session = new TransactionalSession(this.sessionManager.getSession(this.sessionKey));

        final Header header = new Header(slice(this.data, Header.SIZE));
        final Packet packet = new Packet(session, header, this.data);

        blackhole.consume(packet);
    }

    /**
     * Everything the event loop does per datagram before the handoff. Mirrors
     * {@code UdpParserBase.parse} plus the pre-{@code enqueue} half of {@code ParserBase.transmit}.
     */
    @Benchmark
    public void decodeAndBuildFlows(final Blackhole blackhole) throws Exception {
        this.data.resetReaderIndex();

        final TransactionalSession session = new TransactionalSession(this.sessionManager.getSession(this.sessionKey));

        final Header header = new Header(slice(this.data, Header.SIZE));
        final Packet packet = new Packet(session, header, this.data);

        final ExporterIdentity exporter =
                new ExporterIdentity.NetflowIpfix(session.getRemoteAddress(), header.sourceId);

        // NetFlow v9 counts export packets, so the increment is 1 (see FlowPacket.getSequenceIncrement).
        blackhole.consume(session.verifySequenceNumber(exporter, this.sequenceNumber++, 1));

        // .toList() is not incidental: ParserBase.transmit materialises the stream on this thread,
        // so the flow objects are constructed here and not in the pool.
        final List<Flow> flows = this.flowBuilder.buildFlows(Instant.EPOCH, packet, exporter).toList();

        blackhole.consume(flows);
    }

    /** Decode a whole datagram against the shared session, returning the flows it carried. */
    private List<Flow> decode(final ByteBuf buffer) throws Exception {
        buffer.resetReaderIndex();

        final Session session = new TransactionalSession(this.sessionManager.getSession(this.sessionKey));
        final Header header = new Header(slice(buffer, Header.SIZE));
        final Packet packet = new Packet(session, header, buffer);

        return this.flowBuilder.buildFlows(Instant.EPOCH, packet,
                new ExporterIdentity.NetflowIpfix(session.getRemoteAddress(), header.sourceId)).toList();
    }

    /**
     * The fixture in a pooled direct buffer, matching what the event loop hands the parser.
     *
     * <p>Loaded from the classpath rather than a cwd-relative path so the JMH main can be run from
     * anywhere, as the sibling IPFIX benchmarks already do.
     */
    private static ByteBuf buffer(final String fixture) throws Exception {
        try (InputStream in = Netflow9ReadThreadBenchmark.class.getResourceAsStream(FOLDER + fixture)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not on the classpath: " + FOLDER + fixture);
            }
            final byte[] bytes = in.readAllBytes();
            final ByteBuf buffer = ByteBufAllocator.DEFAULT.directBuffer(bytes.length);
            buffer.writeBytes(bytes);
            return buffer;
        }
    }
}
