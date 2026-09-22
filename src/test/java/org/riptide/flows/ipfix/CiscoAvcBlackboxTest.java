/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

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
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.OptionListener;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.ExporterInterfaceTable;
import org.riptide.snmp.IfInfo;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * A Catalyst 8000V (IOS-XE 26.01.02) exporting Flexible NetFlow with NBAR2 over IPFIX, driven
 * through the real parse path. Every byte is captured, not constructed; the capture and the router
 * configuration live in nl6 under {@code testdata/cisco-avc/capture/}.
 *
 * <p>What the router sends, decoded from the capture:</p>
 * <pre>
 * observation domain 6
 *   options template 256  scope ingressInterface -> interfaceName, interfaceDescription, egressInterface
 *   options template 257  scope applicationId    -> applicationName(24), applicationDescription(55)
 * observation domain 256
 *   data template 258     ... applicationId (95, 4 bytes), PEN 9 connection id (12242, dropped),
 *                         PEN 9 HTTP URI statistics (9357, httpUri), PEN 9 HTTP host (12235, httpHost) ...
 * </pre>
 * The option tables and the flow records arrive under different observation domains, which is
 * why every table lookup here falls back from the exact identity to the device address.
 */
public class CiscoAvcBlackboxTest {

    static final Path FOLDER = Paths.get("src/test/resources/flows");

    static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    static final long OPTIONS_DOMAIN = 6L;
    static final long FLOWS_DOMAIN = 256L;

    static final long HTTP = 0x03000050L;
    static final long DNS = 0x03000035L;
    static final long SSH = 0x03000016L;
    static final long ICMP = 0x01000001L;
    static final long PANA_L7_UNKNOWN = 0x0d000001L;

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

    List<Packet> parse(final String fixture) throws Exception {
        final ByteBuf buf = Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve(fixture)));
        final List<Packet> packets = new ArrayList<>();
        do {
            packets.add(new Packet(this.session, new Header(slice(buf, Header.SIZE)), buf));
        } while (buf.isReadable());
        return packets;
    }

    List<Flow> flows(final String fixture) throws Exception {
        final var builder = new IpFixFlowBuilder(new ValueConversionService(IpfixRawFlow.class, VISITORS));
        final List<Flow> flows = new ArrayList<>();
        for (final Packet packet : parse(fixture)) {
            builder.buildFlows(Instant.EPOCH, packet).forEach(flows::add);
        }
        return flows;
    }

    static Object value(final Collection<Value<?>> values, final String name) {
        return values.stream().filter(v -> v.getName().equals(name)).findFirst().map(Value::getValue).orElse(null);
    }

    /** Scope values arrive as {@link org.riptide.flows.parser.ie.values.UnsignedValue}, backed by Guava. */
    static long unsigned(final Object value) {
        return ((UnsignedLong) value).longValue();
    }

    @Test
    public void theHttpDataPacketCarriesPackedApplicationIds() throws Exception {
        parse("ipfix_test_cisco_c8000v_avc_tpl258.dat");

        final List<Flow> flows = flows("ipfix_test_cisco_c8000v_avc_data258_http.dat");

        assertThat(flows).hasSize(17);
        assertThat(flows).extracting(Flow::getApplicationId)
                .as("every record names an application; the L7 engine reports unknown (13/1) or binary-over-http (13/0x1af) beside the L4 http rows")
                .containsOnly(HTTP, PANA_L7_UNKNOWN, 0x0d0001afL);
        assertThat(flows.stream().filter(f -> f.getApplicationId() == HTTP))
                .as("the http rows are the port-80 conversations")
                .allSatisfy(f -> assertThat(List.of(f.getSrcPort(), f.getDstPort())).contains(80));
    }

    @Test
    public void theIcmpDataPacketCarriesAnIanaL3Id() throws Exception {
        parse("ipfix_test_cisco_c8000v_avc_tpl258.dat");

        final List<Flow> flows = flows("ipfix_test_cisco_c8000v_avc_data258_icmp.dat");

        assertThat(flows).extracting(Flow::getApplicationId).containsExactly(ICMP, 0x0d0001dfL, 0x0d0001dfL);
    }

    /**
     * PEN 9 / 12235 and 9357 as the router encodes them: the host behind its six-byte application
     * prefix, the URI as one {@code URI NUL count} pair holding the first path segment only. Both
     * ride the ingress (flowDirection 0) http record; the egress record and every record the L7
     * engine reclassified carry the prefix alone and an empty URI field.
     */
    @Test
    public void theHttpDataPacketCarriesTheHostAndUriOnTheIngressHttpRecords() throws Exception {
        parse("ipfix_test_cisco_c8000v_avc_tpl258.dat");

        final List<Flow> flows = flows("ipfix_test_cisco_c8000v_avc_data258_http.dat");

        final List<Flow> requests = flows.stream()
                .filter(f -> f.getApplicationId() == HTTP && f.getDirection() == Flow.Direction.INGRESS)
                .toList();
        assertThat(requests).hasSize(5);
        assertThat(requests).extracting(Flow::getHttpHost, Flow::getHttpUri).containsExactly(
                tuple("www.example.com", "/"),
                tuple("www.example.com", "/api"),
                tuple("www.example.com", "/static"),
                tuple("www.example.com", "/api"),
                tuple("api.example.com", "/"));
        assertThat(flows).filteredOn(f -> !requests.contains(f))
                .as("the other 12 records carry the prefix alone and an empty URI field, which decode to empty, not null: the template has the elements")
                .extracting(Flow::getHttpHost, Flow::getHttpUri)
                .containsOnly(tuple("", ""));
    }

    @Test
    public void theIcmpDataPacketCarriesNeitherHostNorUri() throws Exception {
        parse("ipfix_test_cisco_c8000v_avc_tpl258.dat");

        final List<Flow> flows = flows("ipfix_test_cisco_c8000v_avc_data258_icmp.dat");

        assertThat(flows).extracting(Flow::getHttpHost, Flow::getHttpUri).containsOnly(tuple("", ""));
    }

    @Test
    public void theApplicationTableReachesTheTapScopedByApplicationId() throws Exception {
        parse("ipfix_test_cisco_c8000v_avc_apptable257_tpl.dat");
        parse("ipfix_test_cisco_c8000v_avc_apptable257_http.dat");

        final var http = this.offered.stream()
                .filter(o -> HTTP == unsigned(value(o.scopes(), "applicationId")))
                .findFirst().orElseThrow();

        assertThat(http.identity())
                .isEqualTo(new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OPTIONS_DOMAIN));
        assertThat(String.valueOf(value(http.values(), "applicationName")).replace("\0", "").trim()).isEqualTo("http");
        assertThat(String.valueOf(value(http.values(), "applicationDescription")).replace("\0", "").trim())
                .isEqualTo("World Wide Web traffic");
    }

    @Test
    public void theInterfaceTableArrivesUnderTheOptionsDomain() throws Exception {
        parse("ipfix_test_cisco_c8000v_avc_iftable256.dat");

        assertThat(this.offered).hasSize(4);
        assertThat(this.offered).allSatisfy(o -> assertThat(o.identity())
                .isEqualTo(new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), OPTIONS_DOMAIN)));
        final var gi2 = this.offered.stream()
                .filter(o -> "Gi2".equals(String.valueOf(value(o.values(), "interfaceName")).replace("\0", "").trim()))
                .findFirst().orElseThrow();
        assertThat(unsigned(value(gi2.scopes(), "ingressInterface"))).isEqualTo(2L);
    }

    @Test
    public void flowRecordsArriveUnderADifferentDomainThanTheTables() throws Exception {
        final List<Packet> packets = parse("ipfix_test_cisco_c8000v_avc_tpl258.dat");

        assertThat(packets.getFirst().header.observationDomainId).isEqualTo(FLOWS_DOMAIN);
    }

    @Test
    public void theRealTableNamesTheHttpFlowsAcrossTheDomainSplit() throws Exception {
        final var table = new ExporterApplicationTable(new SnmpOptionsConfig(), new SessionAdmissionConfig(), new MetricRegistry());
        final Session tapped = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), table);
        for (final String fixture : List.of("ipfix_test_cisco_c8000v_avc_apptable257_tpl.dat",
                "ipfix_test_cisco_c8000v_avc_apptable257_dns.dat",
                "ipfix_test_cisco_c8000v_avc_apptable257_http.dat",
                "ipfix_test_cisco_c8000v_avc_apptable257_ssh.dat")) {
            final ByteBuf buf = Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve(fixture)));
            new Packet(tapped, new Header(slice(buf, Header.SIZE)), buf);
        }
        final var flowIdentity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), FLOWS_DOMAIN);

        assertThat(table.lookup(flowIdentity, HTTP)).map(ApplicationInfo::name).contains("http");
        assertThat(table.lookup(flowIdentity, DNS)).map(ApplicationInfo::name).contains("dns");
        assertThat(table.lookup(flowIdentity, SSH)).map(ApplicationInfo::name).contains("ssh");
        assertThat(table.lookup(flowIdentity, ICMP))
                .as("icmp is not in the three table packets kept as fixtures; an unresolved id stays unresolved")
                .isEmpty();
    }

    @Test
    public void theRealInterfaceTableNamesGi2ForAFlowOnTheFlowsDomain() throws Exception {
        final var table = new ExporterInterfaceTable(new SnmpOptionsConfig(), new SessionAdmissionConfig(), new MetricRegistry());
        final Session tapped = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), table);
        final ByteBuf buf = Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("ipfix_test_cisco_c8000v_avc_iftable256.dat")));
        new Packet(tapped, new Header(slice(buf, Header.SIZE)), buf);
        final var flowIdentity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), FLOWS_DOMAIN);

        assertThat(table.lookup(flowIdentity, 2)).map(IfInfo::name).contains("Gi2");
    }
}
