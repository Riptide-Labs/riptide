package org.riptide.flows.parser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

import static org.riptide.flows.utils.BufferUtils.slice;

@SpringBootTest
public class Netflow9TimeSwitchedParserTest {
    private static final Path FOLDER = Paths.get("src/test/resources/flows");

    @Autowired
    @Qualifier("netflow9ValueConversionService")
    private ValueConversionService valueConversionService;

    @Test
    void verifyFirstAndLastSwitched() throws Exception {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.ofEpochSecond(1000);
        raw.sysUpTime = Duration.ofMillis(1000);
        raw.FIRST_SWITCHED = Duration.ofMillis(2000);
        raw.LAST_SWITCHED = Duration.ofMillis(3000);
        final var flowMessage = new Netflow9FlowBuilder(valueConversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(1001000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(1002000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(1001000L));
    }

    @Test
    void verifyFlowStartAndEndMs() throws Exception {
        final var raw = new Netflow9RawFlow();
        raw.unixSecs = Instant.ofEpochSecond(1000);
        raw.sysUpTime = Duration.ofMillis(1000);
        raw.flowStartMilliseconds = Instant.ofEpochMilli(2001000);
        raw.flowEndMilliseconds = Instant.ofEpochMilli(2002000);
        final var flowMessage = new Netflow9FlowBuilder(valueConversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(2001000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(2002000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(2001000L));
    }

    @Test
    void verifyCaptureFile() throws Exception {
        final var filename = "/flows/netflow9_test_time_parser_timeswitched.dat";
        final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
        try (final var is = getClass().getResourceAsStream(filename)) {
            Assertions.assertThat(is).isNotNull();
            final ByteBuffer buffer = ByteBuffer.allocate(is.available());
            buffer.put(is.readAllBytes());
            buffer.flip();

            final ByteBuf buf = Unpooled.wrappedBuffer(buffer);
            do {
                final Header header = new Header(slice(buf, Header.SIZE));
                final Packet packet = new Packet(session, header, buf);
                final Netflow9FlowBuilder builder = new Netflow9FlowBuilder(valueConversionService);
                final var flows = builder.buildFlows(Instant.EPOCH, packet);
                Assertions.assertThat(packet.header.versionNumber).isEqualTo(0x0009);
                Assertions.assertThat(flows)
                        .allSatisfy(flowMessage -> {
                            Assertions.assertThat(flowMessage.getFirstSwitched()).isNotNull();
                            Assertions.assertThat(flowMessage.getLastSwitched()).isNotNull();
                            Assertions.assertThat(flowMessage.getDeltaSwitched()).isNotNull();
                        });

            } while (buf.isReadable());
        }
    }
}
