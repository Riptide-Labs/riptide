package org.riptide.benchmarks.parser.ipfix;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.mapstruct.factory.Mappers;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
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
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Source;

import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.riptide.flows.utils.BufferUtils.slice;

@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class IpfixFullBenchmark {

    public static final InetAddress LOOPBACK_ADDRESS = InetAddress.getLoopbackAddress();
    public static final EnrichedFlow.FlowMapper FLOW_MAPPER = Mappers.getMapper(EnrichedFlow.FlowMapper.class);
    private final ValueConversionService converter = new ValueConversionService(IpfixRawFlow.class, List.of(
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

    private final IpFixFlowBuilder ipFixFlowBuilder = new IpFixFlowBuilder(converter);

    private ByteBuf buffer;
    private TcpSession session;

    @Setup
    public void setup() throws Exception {
        final URL resourceURL = IpfixParserBenchmark.class.getResource("/flows/ipfix.dat");
        final var bytes = Files.readAllBytes(Paths.get(resourceURL.toURI()));
        this.buffer = Unpooled.wrappedBuffer(bytes);

        this.session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
    }

    @Benchmark
    public void fullPacket(final Blackhole blackhole) throws Exception {
        this.buffer.resetReaderIndex();

        final var header = new Header(slice(this.buffer, Header.SIZE));
        final var packet = new Packet(session, header, slice(this.buffer, header.length - Header.SIZE));

        final var flows = ipFixFlowBuilder.buildFlows(Instant.EPOCH, packet);

        final var enrichedFlows = flows
                .map(flow -> FLOW_MAPPER.enrichedFlow(new Source("", LOOPBACK_ADDRESS), flow))
                .toList();

        blackhole.consume(enrichedFlows);
    }
}
