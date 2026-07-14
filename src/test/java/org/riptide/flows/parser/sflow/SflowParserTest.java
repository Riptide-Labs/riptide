/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.sflow.proto.Datagram;
import org.riptide.flows.parser.sflow.proto.InterfaceValue;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.pipeline.Identity;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SflowParserTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    // --- XDR fixture builders -----------------------------------------------------

    private static ByteBuf buf() {
        return Unpooled.buffer();
    }

    private static ByteBuf u32(final ByteBuf b, final long... values) {
        for (final long v : values) {
            b.writeInt((int) v);
        }
        return b;
    }

    /** Datagram header for an IPv4 agent, followed by pre-encoded samples. */
    private static ByteBuf datagram(final String agent, final long subAgentId, final ByteBuf... samples) throws Exception {
        final ByteBuf b = buf();
        u32(b, 5, 1); // version, agent address type IPv4
        b.writeBytes(InetAddress.getByName(agent).getAddress());
        u32(b, subAgentId, 1000 /* sequence */, 123456 /* uptime */, samples.length);
        for (final ByteBuf sample : samples) {
            b.writeBytes(sample);
        }
        return b;
    }

    /** Wraps sample payload in the (type, length) TLV. */
    private static ByteBuf sample(final long type, final ByteBuf payload) {
        final ByteBuf b = buf();
        u32(b, type, payload.readableBytes());
        b.writeBytes(payload);
        return b;
    }

    /** Compact flow_sample body up to num_records, followed by pre-encoded records. */
    private static ByteBuf flowSampleBody(final long rate, final long input, final long output, final ByteBuf... records) {
        final ByteBuf b = buf();
        u32(b, 42 /* sequence */, 0x01000005L /* source_id */, rate, 100000 /* pool */, 0 /* drops */, input, output, records.length);
        for (final ByteBuf record : records) {
            b.writeBytes(record);
        }
        return b;
    }

    private static ByteBuf record(final long format, final ByteBuf payload) {
        final ByteBuf b = buf();
        u32(b, format, payload.readableBytes());
        b.writeBytes(payload);
        return b;
    }

    /** sampled_header record payload wrapping the given raw frame bytes. */
    private static ByteBuf sampledHeader(final int headerProtocol, final long frameLength, final ByteBuf frame) {
        final ByteBuf b = buf();
        u32(b, headerProtocol, frameLength, 4 /* stripped */, frame.readableBytes());
        b.writeBytes(frame);
        return b;
    }

    /** Ethernet frame: optional 802.1Q tags, then an IPv4+TCP header. */
    private static ByteBuf ethernetIpv4Tcp(final int... vlanTags) throws Exception {
        final ByteBuf b = buf();
        b.writeZero(12); // MACs
        for (final int vlan : vlanTags) {
            b.writeShort(0x8100);
            b.writeShort(vlan);
        }
        b.writeShort(0x0800);
        return ipv4Tcp(b);
    }

    private static ByteBuf ipv4Tcp(final ByteBuf b) throws Exception {
        b.writeByte(0x45); // version 4, IHL 5
        b.writeByte(0xB8); // tos
        b.writeShort(64);  // total length
        b.writeShort(7);   // identification
        b.writeShort(0x4000); // DF, fragment offset 0
        b.writeByte(63);   // ttl
        b.writeByte(6);    // TCP
        b.writeShort(0);   // checksum
        b.writeBytes(InetAddress.getByName("192.0.2.1").getAddress());
        b.writeBytes(InetAddress.getByName("198.51.100.2").getAddress());
        b.writeShort(443); // src port
        b.writeShort(51234); // dst port
        u32(b, 1, 2);      // seq + ack
        b.writeByte(0x50); // data offset
        b.writeByte(0x18); // flags: PSH|ACK
        return b;
    }

    private static List<Flow> flows(final ByteBuf datagram) throws InvalidPacketException {
        return new Datagram(datagram).buildFlows(NOW).toList();
    }

    // --- tests ---------------------------------------------------------------------

    @Test
    public void compactFlowSampleDecodesEthernetVlanIpv4Tcp() throws Exception {
        final var frame = ethernetIpv4Tcp(42);
        final var body = flowSampleBody(512, 7, 9, record(1, sampledHeader(1, 1500, frame)));
        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getFlowProtocol()).isEqualTo(Flow.FlowProtocol.SFLOW);
        assertThat(flow.getSrcAddr()).isEqualTo(InetAddress.getByName("192.0.2.1"));
        assertThat(flow.getDstAddr()).isEqualTo(InetAddress.getByName("198.51.100.2"));
        assertThat(flow.getSrcPort()).isEqualTo(443);
        assertThat(flow.getDstPort()).isEqualTo(51234);
        assertThat(flow.getProtocol()).isEqualTo(6);
        assertThat(flow.getTcpFlags()).isEqualTo(0x18);
        assertThat(flow.getTos()).isEqualTo(0xB8);
        assertThat(flow.getVlan()).isEqualTo(42);
        assertThat(flow.getIpProtocolVersion()).isEqualTo(4);
        assertThat(flow.getInputSnmp()).isEqualTo(7);
        assertThat(flow.getOutputSnmp()).isEqualTo(9);
        assertThat(flow.getBytes()).isEqualTo(1500L * 512);
        assertThat(flow.getPackets()).isEqualTo(512);
        assertThat(flow.getSamplingInterval()).isEqualTo(512.0);
        assertThat(flow.getFirstSwitched()).isEqualTo(NOW);
        assertThat(flow.getLastSwitched()).isEqualTo(NOW);
    }

    @Test
    public void identityComesFromThePayloadNotTheSocket() throws Exception {
        final var body = flowSampleBody(1, 1, 1);
        final var datagram = new Datagram(datagram("10.1.1.1", 7, sample(1, body)));

        final var identity = datagram.identity(InetAddress.getByName("192.0.2.9"));

        assertThat(identity).isEqualTo(new ExporterIdentity.Sflow(InetAddress.getByName("10.1.1.1"), 7));
        assertThat(datagram.getObservationDomainId()).isEqualTo(7);
        assertThat(datagram.getSequenceNumber()).isEqualTo(1000);
    }

    @Test
    public void counterAndVendorSamplesAreSkippedSilently() throws Exception {
        final var counters = buf();
        u32(counters, 99, 1, 2, 3); // opaque counter payload
        final var vendor = buf();
        u32(vendor, 1, 2);
        final var body = flowSampleBody(1, 1, 1);

        final var flows = flows(datagram("10.1.1.1", 0,
                sample(2, counters),                 // counters_sample
                sample((4711L << 12) | 1, vendor),   // vendor enterprise
                sample(1, body)));

        assertThat(flows).hasSize(1);
    }

    @Test
    public void expandedFlowSampleParses() throws Exception {
        final var b = buf();
        u32(b, 42, 0, 5 /* source_id type, index */, 256, 100000, 0,
                0, 7 /* input: format, value */, 1, 3 /* output: drop reason */, 0 /* records */);

        final var flow = flows(datagram("10.1.1.1", 0, sample(3, b))).getFirst();

        assertThat(flow.getInputSnmp()).isEqualTo(7);
        assertThat(flow.getOutputSnmp()).isEqualTo(0); // drop reason is not an ifIndex
        assertThat(flow.getPackets()).isEqualTo(256);
    }

    @Test
    public void truncatedTcpHeaderKeepsPortsAndDropsFlags() throws Exception {
        final var frame = ethernetIpv4Tcp();
        final var truncated = frame.slice(0, frame.readableBytes() - 10); // cut seq/ack/flags
        final var body = flowSampleBody(2, 1, 1, record(1, sampledHeader(1, 900, buf().writeBytes(truncated))));

        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getSrcPort()).isEqualTo(443);
        assertThat(flow.getTcpFlags()).isEqualTo(0);
        assertThat(flow.getBytes()).isEqualTo(1800);
    }

    @Test
    public void undecodablePayloadDegradesToFloorFlow() throws Exception {
        final var arp = buf();
        arp.writeZero(12);
        arp.writeShort(0x0806); // ARP
        arp.writeZero(28);
        final var body = flowSampleBody(128, 7, 9, record(1, sampledHeader(1, 64, arp)));

        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getSrcAddr()).isNull();
        assertThat(flow.getDstAddr()).isNull();
        assertThat(flow.getBytes()).isEqualTo(64L * 128);
        assertThat(flow.getPackets()).isEqualTo(128);
        assertThat(flow.getInputSnmp()).isEqualTo(7);
    }

    @Test
    public void sampledIpv4StructRecordParses() throws Exception {
        final var b = buf();
        u32(b, 800, 17); // length, UDP
        b.writeBytes(InetAddress.getByName("192.0.2.7").getAddress());
        b.writeBytes(InetAddress.getByName("198.51.100.8").getAddress());
        u32(b, 5353, 5353, 0, 0x10); // ports, tcp flags, tos
        final var body = flowSampleBody(4, 1, 1, record(3, b));

        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getSrcAddr()).isEqualTo(InetAddress.getByName("192.0.2.7"));
        assertThat(flow.getProtocol()).isEqualTo(17);
        assertThat(flow.getSrcPort()).isEqualTo(5353);
        assertThat(flow.getTos()).isEqualTo(0x10);
        assertThat(flow.getBytes()).isEqualTo(3200);
    }

    @Test
    public void extendedRecordsMapToFlowFields() throws Exception {
        final var sw = buf();
        u32(sw, 100, 0, 200, 0); // src/dst vlan + priorities

        final var router = buf();
        u32(router, 1); // IPv4 next hop
        router.writeBytes(InetAddress.getByName("10.0.0.254").getAddress());
        u32(router, 24, 16); // masks

        final var gateway = buf();
        u32(gateway, 1);
        gateway.writeBytes(InetAddress.getByName("10.0.0.253").getAddress());
        u32(gateway, 64999 /* own AS */, 64500 /* src AS */, 64998 /* peer */,
                2 /* path segments */, 2, 2, 65001, 65002 /* seg 1 */, 2, 1, 64999 /* seg 2 */,
                0 /* communities */, 100 /* localpref */);

        final var body = flowSampleBody(1, 1, 1,
                record(1001, sw), record(1002, router), record(1003, gateway));

        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getVlan()).isEqualTo(100);
        assertThat(flow.getNextHop()).isEqualTo(InetAddress.getByName("10.0.0.254"));
        assertThat(flow.getSrcMaskLen()).isEqualTo(24);
        assertThat(flow.getDstMaskLen()).isEqualTo(16);
        assertThat(flow.getSrcAs()).isEqualTo(64500);
        assertThat(flow.getDstAs()).isEqualTo(64999); // last AS of the last path segment
    }

    @Test
    public void ipv6WithExtensionHeaderAndUdpParses() throws Exception {
        final var frame = buf();
        frame.writeZero(12);
        frame.writeShort(0x86DD);
        u32(frame, (6L << 28) | (0xB8L << 20)); // version 6, traffic class 0xB8
        frame.writeShort(16); // payload length
        frame.writeByte(0);   // next: hop-by-hop
        frame.writeByte(64);  // hop limit
        frame.writeBytes(InetAddress.getByName("2001:db8::1").getAddress());
        frame.writeBytes(InetAddress.getByName("2001:db8::2").getAddress());
        frame.writeByte(17);  // ext: next header UDP
        frame.writeByte(0);   // ext length: (0+1)*8 bytes total
        frame.writeZero(6);
        frame.writeShort(53); // src port
        frame.writeShort(5300);
        final var body = flowSampleBody(16, 1, 1, record(1, sampledHeader(1, 120, frame)));

        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getIpProtocolVersion()).isEqualTo(6);
        assertThat(flow.getSrcAddr()).isEqualTo(InetAddress.getByName("2001:db8::1"));
        assertThat(flow.getProtocol()).isEqualTo(17);
        assertThat(flow.getSrcPort()).isEqualTo(53);
        assertThat(flow.getDstPort()).isEqualTo(5300);
        assertThat(flow.getTos()).isEqualTo(0xB8);
    }

    @Test
    public void qinqOuterTagWins() throws Exception {
        final var frame = buf();
        frame.writeZero(12);
        frame.writeShort(0x88A8);
        frame.writeShort(300); // outer
        frame.writeShort(0x8100);
        frame.writeShort(400); // inner
        frame.writeShort(0x0800);
        ipv4Tcp(frame);
        final var body = flowSampleBody(1, 1, 1, record(1, sampledHeader(1, 64, frame)));

        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getVlan()).isEqualTo(300);
        assertThat(flow.getSrcAddr()).isEqualTo(InetAddress.getByName("192.0.2.1"));
    }

    @Test
    public void interfaceFormatBitBoundaries() {
        assertThat(InterfaceValue.compact(0).ifIndex()).isEqualTo(0);
        assertThat(InterfaceValue.compact(7).ifIndex()).isEqualTo(7);
        assertThat(InterfaceValue.compact(0x3FFFFFFEL).ifIndex()).isEqualTo(0x3FFFFFFE);
        assertThat(InterfaceValue.compact(0x3FFFFFFFL).ifIndex()).isEqualTo(0); // device-internal
        assertThat(InterfaceValue.compact(0x40000107L).ifIndex()).isEqualTo(0); // format 1: drop reason
        assertThat(InterfaceValue.compact(0x80000002L).ifIndex()).isEqualTo(0); // format 2: multiple
        assertThat(InterfaceValue.expanded(0, 7).ifIndex()).isEqualTo(7);
        assertThat(InterfaceValue.expanded(1, 7).ifIndex()).isEqualTo(0);
        assertThat(InterfaceValue.expanded(0, 0xFFFFFFFFL).ifIndex()).isEqualTo(0); // would overflow
    }

    @Test
    public void handlesPeeksTheXdrVersionWord() {
        final var parser = new SflowUdpParser("test", (source, flow) -> { },
                new Identity("default", "default", "here", "default"), new MetricRegistry());

        assertThat(parser.handles(u32(buf(), 5))).isTrue();
        assertThat(parser.handles(buf().writeShort(0x0005))).isFalse();  // NetFlow v5
        assertThat(parser.handles(u32(buf(), 0x000A0000L))).isFalse();   // IPFIX-ish
        assertThat(parser.handles(buf())).isFalse();
    }

    @Test
    public void malformedFramingThrows() throws Exception {
        final var badVersion = u32(buf(), 4, 1);

        assertThatThrownBy(() -> new Datagram(badVersion)).isInstanceOf(InvalidPacketException.class);

        final var truncated = datagram("10.1.1.1", 0); // claims 0 samples — now claim 1
        truncated.setInt(24, 1); // num_samples word for an IPv4 agent datagram
        assertThatThrownBy(() -> new Datagram(truncated)).isInstanceOf(InvalidPacketException.class);
    }

    @Test
    public void hugeHeaderLengthStaysLenient() throws Exception {
        // header_length 0xFFFFFFFF must not abort the datagram (a negative int cast
        // used to turn slice() into an unchecked throw); decode what is readable
        final var frame = ethernetIpv4Tcp();
        final var record = buf();
        u32(record, 1, 1500, 4, 0xFFFFFFFFL); // header_protocol, frame_length, stripped, header_length
        record.writeBytes(frame);
        final var body = flowSampleBody(2, 1, 1, record(1, record));

        final var flow = flows(datagram("10.1.1.1", 0, sample(1, body))).getFirst();

        assertThat(flow.getSrcAddr()).isEqualTo(InetAddress.getByName("192.0.2.1"));
        assertThat(flow.getBytes()).isEqualTo(3000);
    }

    @Test
    public void undersizedSampleLengthThrowsInvalidPacket() throws Exception {
        final var stub = buf();
        u32(stub, 42, 1); // 8 bytes, far below the 32-byte flow_sample fixed header
        final var d = datagram("10.1.1.1", 0, sample(1, stub));

        assertThatThrownBy(() -> new Datagram(d))
                .isInstanceOf(InvalidPacketException.class)
                .hasMessageContaining("Truncated flow sample header");
    }

    @Test
    public void engineIdClampsInsteadOfCastingNegative() throws Exception {
        final var body = flowSampleBody(1, 1, 1);
        final var d = new Datagram(datagram("10.1.1.1", 0x80000001L, sample(1, body)));

        final var flow = d.buildFlows(NOW).toList().getFirst();

        assertThat(flow.getEngineId()).isEqualTo(Integer.MAX_VALUE); // clamped, not negative
        assertThat(d.identity(InetAddress.getByName("192.0.2.9")))
                .isEqualTo(new ExporterIdentity.Sflow(InetAddress.getByName("10.1.1.1"), 0x80000001L)); // identity keeps the real value
    }
}
