package org.riptide.flows.parser;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.slice;

@Disabled("Disabled, invoke this manually for some metrics")
public class FlowPerformanceTest {

    private static Packet THE_PACKET;

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
    )
    );

    private final IpFixFlowBuilder ipFixFlowBuilder = new IpFixFlowBuilder(converter);

    @BeforeAll
    static void setUp() throws Exception {
        final URL resourceURL = FlowPerformanceTest.class.getResource("/flows/ipfix.dat");
        Objects.requireNonNull(resourceURL);
        try (FileChannel channel = FileChannel.open(Paths.get(resourceURL.toURI()))) {
            final ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            channel.read(buffer);
            buffer.flip();
            final var buf = Unpooled.wrappedBuffer(buffer);
            final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
            final var header = new Header(slice(buf, Header.SIZE));
            final var packet = new Packet(session, header, slice(buf, header.length - Header.SIZE));
            THE_PACKET = packet;
        }
    }

    @Test
    void testTheThing() throws Exception {
        final var instant = Instant.now();

        long sum = 0;

        for (var i = 0; i < 1_000_000_000; i++) {
            sum = ipFixFlowBuilder.buildFlows(instant, THE_PACKET).count();
            if (i % 1_000_000 == 0) {
                System.out.print(".");
            }
        }

        System.out.println(sum);
    }
}
