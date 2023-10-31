package org.riptide.flows.adapter.netflow9;

import org.assertj.core.api.Assertions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.riptide.flows.utils.BufferUtils.slice;

public class Netflow9ConverterTest {

    @Test
    void verifyCanParseNetflow9Flows() throws URISyntaxException, IOException, InvalidPacketException {
        final var flows = getFlowsForPayloadsInSession("/flows/netflow9_template.dat", "/flows/netflow9_records.dat");
        Assertions.assertThat(flows).hasSize(5);

        final var flow = flows.get(4);
        Assertions.assertThat(flow.getNetflowVersion()).isEqualTo(Flow.NetflowVersion.V9);
        Assertions.assertThat(flow.getSrcAddr().getHostAddress()).isEqualTo("10.1.20.85");
        Assertions.assertThat(flow.getSrcAddrHostname()).isEqualTo(Optional.empty());
        Assertions.assertThat(flow.getSrcPort()).isEqualTo(137);
        Assertions.assertThat(flow.getDstAddr().getHostAddress()).isEqualTo("10.1.20.127");
        Assertions.assertThat(flow.getDstAddrHostname()).isEmpty();
        Assertions.assertThat(flow.getDstPort()).isEqualTo(137);
        Assertions.assertThat(flow.getProtocol()).isEqualTo(17); // UDP
        Assertions.assertThat(flow.getNumBytes()).isEqualTo(156L);
        Assertions.assertThat(flow.getInputSnmp()).isEqualTo(369098754);
        Assertions.assertThat(flow.getOutputSnmp()).isEqualTo(0);
        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(1524773519000L)); // Thu Apr 26 16:11:59 EDT 2018
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(1524773527000L)); // Thu Apr 26 16:12:07 EDT 2018
        Assertions.assertThat(flow.getNumPackets()).isEqualTo(2L);
        Assertions.assertThat(flow.getDirection()).isEqualTo(Flow.Direction.INGRESS);
        Assertions.assertThat(flow.getNextHop().getHostAddress()).isEqualTo("0.0.0.0");
        Assertions.assertThat(flow.getNextHopHostname()).isEqualTo(Optional.empty());
        Assertions.assertThat(flow.getVlan()).isNull();
    }

    private List<Flow> getFlowsForPayloadsInSession(final String... resources) throws URISyntaxException, IOException, InvalidPacketException {
        final var payloads = new ArrayList<byte[]>(resources.length);
        for (String resource : resources) {
            URL resourceURL = getClass().getResource(resource);
            payloads.add(Files.readAllBytes(Paths.get(resourceURL.toURI())));
        }
        return getFlowsForPayloadsInSession(payloads);
    }

    private List<Flow> getFlowsForPayloadsInSession(final List<byte[]> payloads) throws InvalidPacketException {
        final var flows = new ArrayList<Flow>();
        final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
        for (byte[] payload : payloads) {
            final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
            final Header header = new Header(slice(buffer, Header.SIZE));
            final Packet packet = new Packet(session, header, buffer);
            packet.getRecords().forEach(rec -> {
                flows.add(new Netflow9FlowBuilder().buildFlow(Instant.EPOCH, rec, (address) -> Optional.empty()));
            });
        }
        return flows;
    }


}
