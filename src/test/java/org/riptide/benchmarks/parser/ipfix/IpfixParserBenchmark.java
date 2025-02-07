package org.riptide.benchmarks.parser.ipfix;


import io.netty.buffer.ByteBuf;
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
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;

import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.riptide.flows.utils.BufferUtils.slice;

@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class IpfixParserBenchmark {

    private ByteBuf buffer;

    @Setup
    public void setup() throws Exception {
        final URL resourceURL = IpfixParserBenchmark.class.getResource("/flows/ipfix.dat");
        final var bytes = Files.readAllBytes(Paths.get(resourceURL.toURI()));
        this.buffer = Unpooled.wrappedBuffer(bytes);
    }

    @Benchmark
    public void parsePacket(final Blackhole blackhole) throws Exception {
        final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));

        this.buffer.resetReaderIndex();
        final var header = new Header(slice(this.buffer, Header.SIZE));
        final var packet = new Packet(session, header, slice(this.buffer, header.length - Header.SIZE));

        blackhole.consume(packet);
    }
}
