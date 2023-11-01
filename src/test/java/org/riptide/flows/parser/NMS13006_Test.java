package org.riptide.flows.parser;

import org.assertj.core.api.Assertions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

import static org.riptide.flows.utils.BufferUtils.slice;

public class NMS13006_Test {
    private static final Path FOLDER = Paths.get("src/test/resources/flows");

    @Test
    void verifyFirstAndLastSwitched() throws Exception {
        final RecordEnrichment enrichment = (address -> Optional.empty());
        final var record = new RecordBuilder();
        record.add(new UnsignedValue("@unixSecs", 1000));
        record.add(new UnsignedValue("@sysUpTime", 1000));
        record.add(new UnsignedValue("FIRST_SWITCHED", 2000));
        record.add(new UnsignedValue("LAST_SWITCHED", 3000));
        final var flowMessage = new Netflow9FlowBuilder().buildFlow(Instant.EPOCH, record.values(), enrichment);

        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(1001000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(1002000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(1001000L));
    }

    @Test
    void verifyFlowStartAndEndMs() throws Exception {
        final RecordEnrichment enrichment = (address -> Optional.empty());
        final var record = new RecordBuilder();
        record.add(new UnsignedValue("@unixSecs", 1000));
        record.add(new UnsignedValue("@sysUpTime", 1000));
        record.add(new UnsignedValue("flowStartMilliseconds", 2001000));
        record.add(new UnsignedValue("flowEndMilliseconds", 2002000));
        final var flowMessage = new Netflow9FlowBuilder().buildFlow(Instant.EPOCH, record.values(), enrichment);

        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(2001000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(2002000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(2001000L));
    }

    @Test
    void verifyCaptureFile() throws Exception {
        final var filename = "nms-13006.dat";
        final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
        try (final FileChannel channel = FileChannel.open(FOLDER.resolve(filename))) {
            final ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            channel.read(buffer);
            buffer.flip();

            final ByteBuf buf = Unpooled.wrappedBuffer(buffer);
            do {
                final Header header = new Header(slice(buf, Header.SIZE));
                final Packet packet = new Packet(session, header, buf);
                final RecordEnrichment enrichment = (address -> Optional.empty());

                packet.getRecords().forEach(r -> {
                            final Netflow9FlowBuilder builder = new Netflow9FlowBuilder();
                            final var flowMessage = builder.buildFlow(Instant.EPOCH, r, enrichment);
                            Assertions.assertThat(flowMessage.getFirstSwitched()).isNotNull();
                            Assertions.assertThat(flowMessage.getLastSwitched()).isNotNull();
                            Assertions.assertThat(flowMessage.getDeltaSwitched()).isNotNull();
                        }
                );

                Assertions.assertThat(packet.header.versionNumber).isEqualTo(0x0009);

            } while (buf.isReadable());
        }
    }
}
