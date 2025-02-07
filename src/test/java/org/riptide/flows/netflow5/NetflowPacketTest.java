package org.riptide.flows.netflow5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Record;
import org.riptide.flows.parser.netflow5.proto.Packet;

public class NetflowPacketTest {

    @Test
    public void canReadValidNetflow5() throws InvalidPacketException {
        execute("/flows/netflow5.dat", flowPacket -> {

            // Verify Header
            assertThat(flowPacket.header.versionNumber).isEqualTo(0x0005);
            assertThat(flowPacket.header.count).isEqualTo(2);
            assertThat(flowPacket.header.sysUptime).isEqualTo(3381L); // Hex: 0x00000D35
            assertThat(flowPacket.header.unixSecs).isEqualTo(1430591888L); // Hex: 0x55451990
            assertThat(flowPacket.header.unixNSecs).isEqualTo(280328000L); // Hex: 0x10B57740
            assertThat(flowPacket.header.flowSequence).isEqualTo(0L);
            assertThat(flowPacket.header.engineId).isEqualTo(0);
            assertThat(flowPacket.header.engineType).isEqualTo(0);
            assertThat(flowPacket.header.samplingInterval).isEqualTo(0);
            assertThat(flowPacket.header.samplingAlgorithm).isEqualTo(0);

            assertThat(flowPacket.records).hasSize(2);

            assertThat(flowPacket.records.get(0).srcAddr.getHostAddress()).isEqualTo("10.0.2.2");
            assertThat(flowPacket.records.get(0).dstAddr.getHostAddress()).isEqualTo("10.0.2.15");
            assertThat(flowPacket.records.get(0).nextHop.getHostAddress()).isEqualTo("0.0.0.0");
            assertThat(flowPacket.records.get(0).srcPort).isEqualTo(54435);
            assertThat(flowPacket.records.get(0).dstPort).isEqualTo(22);
            assertThat(flowPacket.records.get(0).tcpFlags).isEqualTo(16);
            assertThat(flowPacket.records.get(0).input).isEqualTo(0);
            assertThat(flowPacket.records.get(0).output).isEqualTo(0);
            assertThat(flowPacket.records.get(0).dPkts).isEqualTo(5L);
            assertThat(flowPacket.records.get(0).dOctets).isEqualTo(230L);
            assertThat(flowPacket.records.get(0).first).isEqualTo(1024L * 1024L * 1024L * 4L - 1); // Hex: 0xFFFFFFFF
            assertThat(flowPacket.records.get(0).last).isEqualTo(2577L); // Hex: 0x00000A11
            assertThat(flowPacket.records.get(0).proto).isEqualTo(6);
            assertThat(flowPacket.records.get(0).tos).isEqualTo(0);
            assertThat(flowPacket.records.get(0).srcAs).isEqualTo(0);
            assertThat(flowPacket.records.get(0).dstAs).isEqualTo(0);
            assertThat(flowPacket.records.get(0).srcMask).isEqualTo(0);
            assertThat(flowPacket.records.get(0).dstMask).isEqualTo(0);
            assertThat(flowPacket.records.get(0).egress).isFalse();

            assertThat(flowPacket.records.get(1).srcAddr.getHostAddress()).isEqualTo("10.0.2.15");
            assertThat(flowPacket.records.get(1).dstAddr.getHostAddress()).isEqualTo("10.0.2.2");
            assertThat(flowPacket.records.get(1).nextHop.getHostAddress()).isEqualTo("0.0.0.0");
            assertThat(flowPacket.records.get(1).srcPort).isEqualTo(22);
            assertThat(flowPacket.records.get(1).dstPort).isEqualTo(54435);
            assertThat(flowPacket.records.get(1).tcpFlags).isEqualTo(24);
            assertThat(flowPacket.records.get(1).input).isEqualTo(0);
            assertThat(flowPacket.records.get(1).output).isEqualTo(0);
            assertThat(flowPacket.records.get(1).dPkts).isEqualTo(4L);
            assertThat(flowPacket.records.get(1).dOctets).isEqualTo(304L);
            assertThat(flowPacket.records.get(1).first).isEqualTo(1024L * 1024L * 1024L * 4L - 1); // Hex: 0xFFFFFFFF
            assertThat(flowPacket.records.get(1).last).isEqualTo(2577L); // Hex: 0x00000A11
            assertThat(flowPacket.records.get(1).proto).isEqualTo(6);
            assertThat(flowPacket.records.get(1).tos).isEqualTo(0);
            assertThat(flowPacket.records.get(1).srcAs).isEqualTo(0);
            assertThat(flowPacket.records.get(1).dstAs).isEqualTo(0);
            assertThat(flowPacket.records.get(1).srcMask).isEqualTo(0);
            assertThat(flowPacket.records.get(1).dstMask).isEqualTo(0);
            assertThat(flowPacket.records.get(1).egress).isTrue();
        });
    }

    @Test
    public void canReadInvalidNetflow5_01() throws InvalidPacketException {
        Assertions.assertThatThrownBy(() -> {
            execute("/flows/netflow5_test_invalid01.dat", flowPacket -> {
                throw new IllegalStateException();
            });
        }).isInstanceOf(InvalidPacketException.class);
    }

    @Test
    public void canReadInvalidNetflow5_02() throws InvalidPacketException {
        Assertions.assertThatThrownBy(() -> {
            execute("/flows/netflow5_test_invalid02.dat", flowPacket -> {
                throw new IllegalStateException();
            });
        }).isInstanceOf(InvalidPacketException.class);

    }

    @Test
    public void canReadMicrotikNetflow5() throws InvalidPacketException {
        execute("/flows/netflow5_test_microtik.dat", flowPacket -> {

            // Verify Header
            assertThat(flowPacket.header.versionNumber).isEqualTo(0x0005);
            assertThat(flowPacket.header.count).isEqualTo(30);
            assertThat(flowPacket.header.sysUptime).isEqualTo(27361640L); // Hex: 0x01A18168
            assertThat(flowPacket.header.unixSecs).isEqualTo(1469109117L); // Hex: 0x5790D37D
            assertThat(flowPacket.header.unixNSecs).isEqualTo(514932000L); // Hex: 0x1EB13D20
            assertThat(flowPacket.header.flowSequence).isEqualTo(8140050L);
            assertThat(flowPacket.header.engineId).isEqualTo(0);
            assertThat(flowPacket.header.engineType).isEqualTo(0);
            assertThat(flowPacket.header.samplingInterval).isEqualTo(0);
            assertThat(flowPacket.header.samplingAlgorithm).isEqualTo(0);

            // Verify Last Flow Record
            assertThat(flowPacket.records).hasSize(30);
            assertThat(flowPacket.records.get(29).srcAddr.getHostAddress()).isEqualTo("10.0.8.1");
            assertThat(flowPacket.records.get(29).dstAddr.getHostAddress()).isEqualTo("192.168.0.1");
            assertThat(flowPacket.records.get(29).nextHop.getHostAddress()).isEqualTo("192.168.0.1");
            assertThat(flowPacket.records.get(29).srcPort).isEqualTo(80);
            assertThat(flowPacket.records.get(29).dstPort).isEqualTo(51826);
            assertThat(flowPacket.records.get(29).tos).isEqualTo(40);
            assertThat(flowPacket.records.get(29).input).isEqualTo(13);
            assertThat(flowPacket.records.get(29).output).isEqualTo(46);
            assertThat(flowPacket.records.get(29).dPkts).isEqualTo(13L);
            assertThat(flowPacket.records.get(29).dOctets).isEqualTo(11442L);
            assertThat(flowPacket.records.get(29).first).isEqualTo(27346380L); // Hex: 0x01A145CC
            assertThat(flowPacket.records.get(29).last).isEqualTo(27346380L); // Hex: 0x01A145CC
            assertThat(flowPacket.records.get(29).tcpFlags).isEqualTo(82);
            assertThat(flowPacket.records.get(29).proto).isEqualTo(6);
            assertThat(flowPacket.records.get(29).srcAs).isEqualTo(0);
            assertThat(flowPacket.records.get(29).dstAs).isEqualTo(0);
            assertThat(flowPacket.records.get(29).srcMask).isEqualTo(0);
            assertThat(flowPacket.records.get(29).dstMask).isEqualTo(0);
            assertThat(flowPacket.records.get(29).egress).isFalse();
        });
    }

    @Test
    public void canReadJuniperMX80Netflow5() throws InvalidPacketException {
        execute("/flows/netflow5_test_juniper_mx80.dat", flowPacket -> {

            // Verify Flow Header
            assertThat(flowPacket.header.versionNumber).isEqualTo(0x0005);
            assertThat(flowPacket.header.count).isEqualTo(29);
            assertThat(flowPacket.header.sysUptime).isEqualTo(190649064L); // Hex: 0x0B5D12E8
            assertThat(flowPacket.header.unixSecs).isEqualTo(1469109172L); // Hex: 0x5790D3B4
            assertThat(flowPacket.header.unixNSecs).isEqualTo(00000000L); // Hex: 0x00000000
            assertThat(flowPacket.header.flowSequence).isEqualTo(528678L);
            assertThat(flowPacket.header.engineId).isEqualTo(0);
            assertThat(flowPacket.header.engineType).isEqualTo(0);
            assertThat(flowPacket.header.samplingInterval).isEqualTo(1000);
            assertThat(flowPacket.header.samplingAlgorithm).isEqualTo(0);

            // Verify Last Flow Record
            assertThat(flowPacket.records).hasSize(29);
            assertThat(flowPacket.records.get(28).srcAddr.getHostAddress()).isEqualTo("66.249.92.75");
            assertThat(flowPacket.records.get(28).dstAddr.getHostAddress()).isEqualTo("192.168.0.1");
            assertThat(flowPacket.records.get(28).nextHop.getHostAddress()).isEqualTo("192.168.0.1");
            assertThat(flowPacket.records.get(28).srcPort).isEqualTo(37387);
            assertThat(flowPacket.records.get(28).dstPort).isEqualTo(80);
            assertThat(flowPacket.records.get(28).srcAs).isEqualTo(15169);
            assertThat(flowPacket.records.get(28).dstAs).isEqualTo(64496);
            assertThat(flowPacket.records.get(28).tos).isEqualTo(0);
            assertThat(flowPacket.records.get(28).input).isEqualTo(542);
            assertThat(flowPacket.records.get(28).output).isEqualTo(536);
            assertThat(flowPacket.records.get(28).dPkts).isEqualTo(2L);
            assertThat(flowPacket.records.get(28).dOctets).isEqualTo(104L);
            assertThat(flowPacket.records.get(28).first).isEqualTo(190631000L); // Hex: 0x0B5CCC58
            assertThat(flowPacket.records.get(28).last).isEqualTo(190631000L); // Hex: 0x0B5CCC58
            assertThat(flowPacket.records.get(28).tcpFlags).isEqualTo(16);
            assertThat(flowPacket.records.get(28).proto).isEqualTo(6);
            assertThat(flowPacket.records.get(28).srcAs).isEqualTo(15169);
            assertThat(flowPacket.records.get(28).dstAs).isEqualTo(64496);
            assertThat(flowPacket.records.get(28).srcMask).isEqualTo(19);
            assertThat(flowPacket.records.get(28).dstMask).isEqualTo(24);
            assertThat(flowPacket.records.get(28).egress).isFalse();
        });
    }

    // Verify that all fields can be handled if they were maxed out.
    // This ensures that all fields are converted correctly.
    // For example if a 2 byte unsigned field's value were FFFF, it must be converted to an integer instead of a short.
    // NOTE: This is purely theoretically and does not reflect a REAL WORLD netflow packet.
    @Test
    public void canHandleMaxValuesNetflow5() throws InvalidPacketException {
        // Generate minimal netflow packet with 1 netflow record but maximum values (theoretical values only)
        byte[] bytes = new byte[Header.SIZE + Record.SIZE];
        Arrays.fill(bytes, (byte) 0xFF);
        bytes[0] = 0x00;
        bytes[1] = 0x05;
        bytes[2] = 0x00;
        bytes[3] = 0x01;

        // Parse and Verify
        final ByteBuf buffer = Unpooled.wrappedBuffer(bytes);

        final Header header = new Header(buffer);
        final Packet packet = new Packet(header, buffer);

        // Verify Header
        assertThat(packet.header.sysUptime).isEqualTo(1024L * 1024L * 1024L * 4L - 1); // 2^32-1
        assertThat(packet.header.unixSecs).isEqualTo(1024L * 1024L * 1024L * 4L - 1); // 2^32-1
        assertThat(packet.header.unixNSecs).isEqualTo(1024L * 1024L * 1024L * 4L - 1); // 2^32-1
        assertThat(packet.header.flowSequence).isEqualTo(1024L * 1024L * 1024L * 4L - 1); // 2^32-1
        assertThat(packet.header.engineType).isEqualTo(255); // 2^8-1
        assertThat(packet.header.engineId).isEqualTo(255); // 2^8-1
        assertThat(packet.header.samplingAlgorithm).isEqualTo(4 - 1); // 2^2-1
        assertThat(packet.header.samplingInterval).isEqualTo(16384 - 1); // 2^14-1

        // Verify Body
        assertThat(packet.records).hasSize(1);
        assertThat(packet.records.get(0).srcAddr.getHostAddress()).isEqualTo("255.255.255.255"); // quadruple: (2^8-1, 2^8-1, 2^8-1, 2^8-1)
        assertThat(packet.records.get(0).dstAddr.getHostAddress()).isEqualTo("255.255.255.255"); // quadruple: (2^8-1, 2^8-1, 2^8-1, 2^8-1)
        assertThat(packet.records.get(0).nextHop.getHostAddress()).isEqualTo("255.255.255.255"); // quadruple: (2^8-1, 2^8-1, 2^8-1, 2^8-1)
        assertThat(packet.records.get(0).input).isEqualTo(65536 - 1); // 2^16-1
        assertThat(packet.records.get(0).output).isEqualTo(65536 - 1); // 2^16-1
        assertThat(packet.records.get(0).dPkts).isEqualTo(1024L * 1024L * 1024L * 4 - 1); // 2^32-1
        assertThat(packet.records.get(0).dOctets).isEqualTo(1024L * 1024L * 1024L * 4 - 1); // 2^32-1
        assertThat(packet.records.get(0).first).isEqualTo(1024L * 1024L * 1024L * 4 - 1); // 2^32-1
        assertThat(packet.records.get(0).last).isEqualTo(1024L * 1024L * 1024L * 4 - 1); // 2^32-1
        assertThat(packet.records.get(0).srcPort).isEqualTo(65536 - 1); // 2^16-1
        assertThat(packet.records.get(0).dstPort).isEqualTo(65536 - 1); // 2^16-1
        assertThat(packet.records.get(0).tcpFlags).isEqualTo(255); // 2^8-1
        assertThat(packet.records.get(0).proto).isEqualTo(255); // 2^8-1
        assertThat(packet.records.get(0).tos).isEqualTo(255); // 2^8-1
        assertThat(packet.records.get(0).srcAs).isEqualTo(65536 - 1); // 2^16-1
        assertThat(packet.records.get(0).dstAs).isEqualTo(65536 - 1); // 2^16-1
        assertThat(packet.records.get(0).srcMask).isEqualTo(255); // 2^8-1
        assertThat(packet.records.get(0).dstMask).isEqualTo(255); // 2^8-1
        assertThat(packet.records.get(0).egress).isFalse();
    }

    @Test
    public void canReadJuniperPackets() throws InvalidPacketException {
        execute("/flows/jflow-packet.dat", packet -> {
            assertThat(packet.header.samplingInterval).isEqualTo(20);
            assertThat(packet.header.samplingAlgorithm).isEqualTo(0);
            assertThat(packet.records.size()).isEqualTo(29);
        });
    }

    public void execute(final String resource, final Consumer<Packet> consumer) throws InvalidPacketException {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(consumer);

        final URL resourceURL = Objects.requireNonNull(getClass().getResource(resource));

        try {
            final byte[] contents = Files.readAllBytes(Paths.get(resourceURL.toURI()));
            final ByteBuf buffer = Unpooled.wrappedBuffer(contents);

            final Header header = new Header(slice(buffer, Header.SIZE));
            final Packet packet = new Packet(header, buffer);

            consumer.accept(packet);

        } catch (final URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
