/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.netflow9;

import com.codahale.metrics.MetricRegistry;
import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.classification.ApplicationInfo;
import org.riptide.classification.ExporterApplicationTable;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.Value;
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
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.SnmpOptionsConfig;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * A Cisco IOS router exporting NBAR over NetFlow v9, driven through the real parse path. Every
 * byte is captured, not constructed; the three fixtures predate this test and until it existed
 * were only asserted to parse.
 *
 * <p>What the router sends, decoded from the fixtures (source id 0 on every packet):</p>
 * <pre>
 * opttpl260  options template 260  scope System(4) -> applicationId(95, 4), APPLICATION NAME(96, 24),
 *                                                     APPLICATION DESCRIPTION(94, 55)
 *            15 option rows, all engine 1 (IANA-L3): egp 0x01000008, gre, icmp 0x01000001, eigrp,
 *            ipinip, ospf, hopopt, ggp, st, cbt, igrp, bbnrccmon, nvp-ii, pup, argus
 * tpl262     data template 262, 26 fields, applicationId(95) at 4 bytes, record length 81
 * data262    5 records: 0x01000001 ×2 (icmp), 0x0300007b (udp/123), 0x05000026 ×2 (udp/161)
 * </pre>
 * Only {@code icmp} is in both the data packet and the option table, so the table resolves one
 * captured id and leaves the other two unresolved.
 */
public class CiscoNbarBlackboxTest {

    static final Path FOLDER = Paths.get("src/test/resources/flows");

    static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    static final long SOURCE_ID = 0L;

    static final long ICMP = 0x01000001L;
    static final long EGP = 0x01000008L;
    static final long NTP = 0x0300007bL;
    static final long SNMP = 0x05000026L;

    /** One captured option record as the tap saw it. */
    record Offered(ExporterIdentity identity, Collection<Value<?>> scopes, List<Value<?>> values) {
    }

    final List<Offered> offered = new ArrayList<>();

    final OptionListener recorder = (identity, scopes, values) -> {
        this.offered.add(new Offered(identity, scopes, values));
        return OptionListener.Verdict.UNRECOGNISED;
    };

    final Session session =
            new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), this.recorder);

    static List<Packet> parse(final Session session, final String fixture) throws Exception {
        final ByteBuf buf = Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve(fixture)));
        final List<Packet> packets = new ArrayList<>();
        do {
            packets.add(new Packet(session, new Header(slice(buf, Header.SIZE)), buf));
        } while (buf.isReadable());
        return packets;
    }

    List<Flow> flows(final String fixture) throws Exception {
        final var builder = new Netflow9FlowBuilder(new ValueConversionService(Netflow9RawFlow.class, VISITORS));
        final List<Flow> flows = new ArrayList<>();
        for (final Packet packet : parse(this.session, fixture)) {
            builder.buildFlows(Instant.EPOCH, packet).forEach(flows::add);
        }
        return flows;
    }

    static Object value(final Collection<Value<?>> values, final String name) {
        return values.stream().filter(v -> v.getName().equals(name)).findFirst().map(Value::getValue).orElse(null);
    }

    static String text(final Object value) {
        return String.valueOf(value).replace("\0", "").trim();
    }

    @Test
    public void theDataPacketCarriesPackedApplicationIds() throws Exception {
        parse(this.session, "netflow9_test_cisco_nbar_tpl262.dat");

        final List<Flow> flows = flows("netflow9_test_cisco_nbar_data262.dat");

        assertThat(flows).hasSize(5);
        assertThat(flows).extracting(Flow::getApplicationId)
                .as("field 95 at 4 bytes decodes to engine << 24 | selector, in record order")
                .containsExactly(ICMP, SNMP, ICMP, NTP, SNMP);
    }

    /** The v9 table carries its id as an option field; the only scope is System. */
    @Test
    public void theApplicationTableReachesTheTapWithTheIdInTheFields() throws Exception {
        parse(this.session, "netflow9_test_cisco_nbar_opttpl260.dat");

        assertThat(this.offered).hasSize(15);
        assertThat(this.offered).allSatisfy(o -> {
            assertThat(o.identity())
                    .isEqualTo(new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), SOURCE_ID));
            assertThat(o.scopes()).extracting(Value::getName).containsExactly("SCOPE:SYSTEM");
            assertThat(value(o.values(), "applicationId")).isInstanceOf(UnsignedLong.class);
        });
        final var egp = this.offered.stream()
                .filter(o -> EGP == ((UnsignedLong) value(o.values(), "applicationId")).longValue())
                .findFirst().orElseThrow();
        assertThat(text(value(egp.values(), "APPLICATION NAME"))).isEqualTo("egp");
        assertThat(text(value(egp.values(), "APPLICATION DESCRIPTION"))).isEqualTo("Exterior Gateway Protocol");
    }

    @Test
    public void theRealTableNamesTheIcmpFlow() throws Exception {
        final var table = new ExporterApplicationTable(new SnmpOptionsConfig(), new SessionAdmissionConfig(), new MetricRegistry());
        final Session tapped = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), table);
        parse(tapped, "netflow9_test_cisco_nbar_opttpl260.dat");
        // exactly what Netflow9UdpParser.parse() constructs for the data packets: session address plus header sourceId
        final var flowIdentity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), SOURCE_ID);

        assertThat(table.lookup(flowIdentity, ICMP)).map(ApplicationInfo::name).contains("icmp");
        assertThat(table.lookup(flowIdentity, EGP))
                .contains(new ApplicationInfo("egp", "Exterior Gateway Protocol"));
        assertThat(table.lookup(flowIdentity, NTP))
                .as("ntp is not among the 15 captured rows; an unresolved id stays unresolved")
                .isEmpty();
    }
}
