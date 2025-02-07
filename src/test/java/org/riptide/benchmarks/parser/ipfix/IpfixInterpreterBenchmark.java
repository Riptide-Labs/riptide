package org.riptide.benchmarks.parser.ipfix;

import io.netty.buffer.Unpooled;
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
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;

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
public class IpfixInterpreterBenchmark {

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

    private Packet packet;


    @Setup
    public void setup() throws Exception {
        final URL resourceURL = IpfixParserBenchmark.class.getResource("/flows/ipfix.dat");
        final var bytes = Files.readAllBytes(Paths.get(resourceURL.toURI()));
        final var buffer = Unpooled.wrappedBuffer(bytes);

        final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));

        final var header = new Header(slice(buffer, Header.SIZE));
        this.packet = new Packet(session, header, slice(buffer, header.length - Header.SIZE));
    }

    @Benchmark
    public void interpretPacket(final Blackhole blackhole) throws Exception {
        final var bytes = ipFixFlowBuilder.buildFlows(Instant.EPOCH, this.packet)
                .mapToLong(Flow::getBytes)
                .sum();

        blackhole.consume(bytes);
    }
}
