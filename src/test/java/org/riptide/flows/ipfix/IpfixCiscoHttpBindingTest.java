/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.InformationElementDatabase;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.StringValue;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Cisco AVC HTTP elements are registered under PEN 9, decode through their typed parsers, and
 * bind by name to the raw flow; the flow reports them, null when the template carried neither.
 */
class IpfixCiscoHttpBindingTest {

    private static final long CISCO = 9L;
    private static final int HTTP_HOST = 12235;
    private static final int HTTP_URI_STATISTICS = 9357;

    private static final List<ValueVisitor<?>> VISITORS = List.of(
            new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(), new InetAddressVisitor(),
            new InstantVisitor(), new IntegerVisitor(), new LongVisitor(), new StringVisitor(),
            new UnsignedLongVisitor());

    private final ValueConversionService conversion = new ValueConversionService(IpfixRawFlow.class, VISITORS);

    @Test
    void theRegistryServesTheHostParserUnderPen9() throws Exception {
        final var element = InformationElementDatabase.instance.lookup(Protocol.IPFIX, CISCO, HTTP_HOST).orElseThrow();

        final Value<?> value = element.parse(null, Unpooled.wrappedBuffer(
                new byte[]{0x03, 0x00, 0x00, 0x50, 0x34, 0x02, 'a', '.', 'b'}));

        assertThat(element.getName()).isEqualTo("httpHost");
        assertThat(value).isInstanceOf(StringValue.class);
        assertThat(value.getValue()).isEqualTo("a.b");
    }

    @Test
    void theRegistryServesTheUriParserUnderPen9() throws Exception {
        final var element = InformationElementDatabase.instance.lookup(Protocol.IPFIX, CISCO, HTTP_URI_STATISTICS).orElseThrow();

        final Value<?> value = element.parse(null, Unpooled.wrappedBuffer(new byte[]{0x2f, 0x00, 0x00, 0x01}));

        assertThat(element.getName()).isEqualTo("httpUri");
        assertThat(value.getValue()).isEqualTo("/");
    }

    /** The registration is keyed by PEN, so the same id without the enterprise bit is IANA's business. */
    @Test
    void theSameIdsWithoutAPenAreNotTheCiscoParsers() {
        assertThat(InformationElementDatabase.instance.lookup(Protocol.IPFIX, HTTP_HOST).map(e -> e.getName()))
                .isNotEqualTo(java.util.Optional.of("httpHost"));
        assertThat(InformationElementDatabase.instance.lookup(Protocol.IPFIX, HTTP_URI_STATISTICS).map(e -> e.getName()))
                .isNotEqualTo(java.util.Optional.of("httpUri"));
    }

    @Test
    void bothValuesBindToTheRawFlowByName() {
        final var raw = new IpfixRawFlow();

        this.conversion.apply(new StringValue("httpHost", "www.example.com"), raw);
        this.conversion.apply(new StringValue("httpUri", "/api"), raw);

        assertThat(raw.httpHost).isEqualTo("www.example.com");
        assertThat(raw.httpUri).isEqualTo("/api");
    }

    @Test
    void boundValuesAreReportedByTheFlow() {
        final var raw = new IpfixRawFlow();
        raw.httpHost = "www.example.com";
        raw.httpUri = "/api";

        final Flow flow = new IpFixFlowBuilder(this.conversion).buildFlow(Instant.EPOCH, raw);

        assertThat(flow.getHttpHost()).isEqualTo("www.example.com");
        assertThat(flow.getHttpUri()).isEqualTo("/api");
    }

    @Test
    void absentValuesReadAsNull() {
        final Flow flow = new IpFixFlowBuilder(this.conversion).buildFlow(Instant.EPOCH, new IpfixRawFlow());

        assertThat(flow.getHttpHost()).isNull();
        assertThat(flow.getHttpUri()).isNull();
    }
}
