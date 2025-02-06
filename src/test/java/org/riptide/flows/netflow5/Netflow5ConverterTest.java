package org.riptide.flows.netflow5;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.netflow5.Netflow5FlowBuilder;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import static org.riptide.flows.utils.BufferUtils.slice;

public class Netflow5ConverterTest {

    @Test
    public void canParseNetflow5Flows() throws URISyntaxException, IOException, InvalidPacketException {
        // Generate flows from existing packet payloads
        final var flows = getFlowsForPayloadsInSession("/flows/netflow5.dat");
        Assertions.assertThat(flows).hasSize(2);

        final var flow = flows.get(0);
        Assertions.assertThat(flow.getFlowProtocol()).isEqualTo(Flow.FlowProtocol.NetflowV5);
        Assertions.assertThat(flow.getFlowRecords()).isEqualTo(2);
        Assertions.assertThat(flow.getFlowSeqNum()).isEqualTo(0L);
        Assertions.assertThat(flow.getEngineId()).isEqualTo(0);
        Assertions.assertThat(flow.getEngineType()).isEqualTo(0);
        Assertions.assertThat(flow.getSamplingInterval()).isEqualTo(0.0);
        Assertions.assertThat(flow.getSamplingAlgorithm()).isEqualTo(Flow.SamplingAlgorithm.Unassigned);
        Assertions.assertThat(flow.getSrcAddr().getHostAddress()).isEqualTo("10.0.2.2");
        Assertions.assertThat(flow.getSrcPort()).isEqualTo(54435);
        Assertions.assertThat(flow.getSrcMaskLen()).isEqualTo(0);
        Assertions.assertThat(flow.getDstAddr().getHostAddress()).isEqualTo("10.0.2.15");
        Assertions.assertThat(flow.getDstPort()).isEqualTo(22);
        Assertions.assertThat(flow.getDstMaskLen()).isEqualTo(0);
        Assertions.assertThat(flow.getTcpFlags()).isEqualTo(16);
        Assertions.assertThat(flow.getProtocol()).isEqualTo(6); // TCP
        Assertions.assertThat(flow.getBytes()).isEqualTo(230L);
        Assertions.assertThat(flow.getInputSnmp()).isEqualTo(0);
        Assertions.assertThat(flow.getOutputSnmp()).isEqualTo(0);
        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.parse("2015-06-21T11:40:52.194328Z"));
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo((Instant.parse("2015-05-02T18:38:07.476328Z")));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo((Instant.parse("2015-06-21T11:40:52.194328Z")));
        Assertions.assertThat(flow.getPackets()).isEqualTo(5L);
        Assertions.assertThat(flow.getDirection()).isEqualTo(Flow.Direction.INGRESS);
        Assertions.assertThat(flow.getNextHop().getHostAddress()).isEqualTo("0.0.0.0");
        Assertions.assertThat(flow.getVlan()).isNull();
    }

    private List<Flow> getFlowsForPayloadsInSession(String... resources) throws InvalidPacketException, URISyntaxException, IOException {
        final var payloads = new ArrayList<byte[]>(resources.length);
        for (String resource : resources) {
            URL resourceURL = getClass().getResource(resource);
            payloads.add(Files.readAllBytes(Paths.get(resourceURL.toURI())));
        }
        return getFlowsForPayloadsInSession(payloads);
    }

    private List<Flow> getFlowsForPayloadsInSession(List<byte[]> payloads) throws InvalidPacketException {
        final List<Flow> flows = new ArrayList<>();
        for (final var payload : payloads) {
            final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
            final Header header = new Header(slice(buffer, Header.SIZE));
                final Packet packet = new Packet(header, buffer);
                packet.buildFlows().forEach(rec -> {
                    final var flowMessage = new Netflow5FlowBuilder().buildFlow(Instant.EPOCH, rec);
                    flows.add(flowMessage);
                });
        }
        return flows;
    }
}
