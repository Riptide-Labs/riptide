/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.session.Field;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.Template;
import org.riptide.flows.parser.session.UdpSessionManager;
import org.riptide.pipeline.Identity;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A malformed packet must only discard that packet (RFC 7011 §10.3) — never the exporter's session
 * state. The regression (#273): any parse error dropped the whole session, so one corrupt packet
 * from a buggy exporter discarded its templates and made all subsequent valid packets unparseable
 * until the exporter re-sent them.
 */
class UdpParserBaseTest {

    private static final InetSocketAddress REMOTE = new InetSocketAddress("10.0.0.1", 51000);
    private static final InetSocketAddress SECOND_REMOTE = new InetSocketAddress("10.0.0.3", 51000);
    private static final InetSocketAddress LOCAL = new InetSocketAddress("10.0.0.2", 4739);
    private static final int TEMPLATE_ID = 256;

    /** T = adds a template; X = malformed (throws); D = requires the template to resolve. */
    private static final byte ADD_TEMPLATE = 'T';
    private static final byte MALFORMED = 'X';
    private static final byte NEEDS_TEMPLATE = 'D';
    /** P = installs a garbage template mid-packet, then fails; Q = probes for that template. */
    private static final byte POISONED = 'P';
    private static final byte PROBE_POISON = 'Q';
    /** S = adds a SECOND template to the same exporter, without failing the packet. */
    private static final byte ADD_SECOND_TEMPLATE = 'S';
    /** O = adds a template under a SECOND observation domain of the same exporter. */
    private static final byte ADD_OTHER_DOMAIN = 'O';
    private static final int GARBAGE_TEMPLATE_ID = 300;
    /** Distinct from {@link #GARBAGE_TEMPLATE_ID}: the poison markers use that one as their
     *  "must have been rolled back" sentinel, and sharing it would entangle the two scenarios. */
    private static final int SECOND_TEMPLATE_ID = 257;
    private static final int OTHER_DOMAIN = 1;

    private ScheduledExecutorService executor;
    private StubParser parser;

    @BeforeEach
    void setUp() {
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.parser = new StubParser();
        this.parser.start();
    }

    @AfterEach
    void tearDown() {
        this.parser.stop();
        this.executor.shutdownNow();
    }

    @Test
    void malformedPacketDoesNotDiscardSessionTemplates() throws Exception {
        // A valid packet installs the exporter's template ...
        parse(ADD_TEMPLATE);

        // ... a malformed packet from the same exporter fails to parse ...
        assertThatThrownBy(() -> parse(MALFORMED)).isInstanceOf(InvalidPacketException.class);

        // ... and the template must still resolve for the next valid packet. (Before the fix the
        // session was dropped here and this threw MissingTemplateException.)
        assertThatCode(() -> parse(NEEDS_TEMPLATE)).doesNotThrowAnyException();
        assertThat(this.parser.templateResolved).isTrue();
    }

    @Test
    void malformedPacketsOwnTemplatesAreRolledBack() throws Exception {
        // Valid history first ...
        parse(ADD_TEMPLATE);

        // ... then a mis-framed packet that installs a garbage template BEFORE its parse fails.
        assertThatThrownBy(() -> parse(POISONED)).isInstanceOf(InvalidPacketException.class);

        // The garbage template from the failed packet must be gone (retaining it would silently
        // mis-decode subsequent data sets), while the earlier valid template survives.
        assertThatCode(() -> parse(PROBE_POISON)).doesNotThrowAnyException();
        assertThat(this.parser.garbageTemplateRetained).isFalse();
        assertThatCode(() -> parse(NEEDS_TEMPLATE)).doesNotThrowAnyException();
        assertThat(this.parser.templateResolved).isTrue();
    }

    /**
     * {@code sessionCount} and {@code templateCount} must report different quantities.
     *
     * <p>{@code sessionCount} was wired to the template total for years — it counted templates while
     * its name promised exporters, so it overstated by however many templates each exporter
     * announces. Nothing caught that, because neither gauge was covered. This pins both by making
     * the two numbers differ: two exporters, three templates.
     *
     * <p>It also pins the stability the old wiring lacked a guard for: re-announcing a template the
     * exporter has already sent must move neither gauge, because {@code addTemplate} replaces the
     * entry under the same template id rather than adding one.
     */
    @Test
    void gaugesReportExportersAndTemplatesSeparately() throws Exception {
        final var registry = new MetricRegistry();
        final var parser = new StubParser(registry);
        parser.start();
        try {
            parse(parser, ADD_TEMPLATE, REMOTE);
            parse(parser, ADD_TEMPLATE, SECOND_REMOTE);
            // a second template for the first exporter only — this is what separates the gauges
            parse(parser, ADD_SECOND_TEMPLATE, REMOTE);

            assertThat(gauge(registry, "sessionCount"))
                    .as("exporters, i.e. (session, observation domain) pairs")
                    .isEqualTo(2);
            assertThat(gauge(registry, "templateCount"))
                    .as("templates across all exporters")
                    .isEqualTo(3);

            // re-announce an already-known template: a same-id replacement, so nothing moves
            parse(parser, ADD_TEMPLATE, REMOTE);

            assertThat(gauge(registry, "sessionCount"))
                    .as("a re-announcement must not invent an exporter")
                    .isEqualTo(2);
            assertThat(gauge(registry, "templateCount"))
                    .as("a re-announcement replaces under the same id, so the total is unchanged")
                    .isEqualTo(3);
        } finally {
            parser.stop();
        }
    }

    /**
     * The defining property of {@code sessionCount}, and the half a session-key-only count would
     * pass silently: one source address announcing two observation domains counts twice. The session
     * key is {@code (remote address, local socket)}, so without the observation domain in the key
     * this reads 1.
     */
    @Test
    void sessionCountSeparatesObservationDomainsOfOneExporter() throws Exception {
        final var registry = new MetricRegistry();
        final var parser = new StubParser(registry);
        parser.start();
        try {
            parse(parser, ADD_TEMPLATE, REMOTE);         // domain 0, template 256
            parse(parser, ADD_SECOND_TEMPLATE, REMOTE);  // domain 0, template 257
            parse(parser, ADD_OTHER_DOMAIN, REMOTE);     // domain 1, template 256

            // the two numbers must differ, or a sessionCount still wired to the template total
            // would read 2 here and pass
            assertThat(gauge(registry, "sessionCount"))
                    .as("one address, two observation domains — two exporting processes")
                    .isEqualTo(2);
            assertThat(gauge(registry, "templateCount"))
                    .as("two templates in the first domain, one in the second")
                    .isEqualTo(3);
        } finally {
            parser.stop();
        }
    }

    /**
     * #546: a stopped parser must report nothing rather than its final counts. Absence is the
     * signal for "not running" — the rule the reloader gauges settled on (#539), where a
     * constant value read as healthy for something that had stopped running entirely.
     */
    @Test
    void aStoppedParserDeregistersItsGauges() throws Exception {
        final var registry = new MetricRegistry();
        final var parser = new StubParser(registry);
        parser.start();
        parse(parser, ADD_TEMPLATE, REMOTE);
        assertThat(gauge(registry, "sessionCount")).isEqualTo(1);

        parser.stop();

        assertThat(registry.getGauges()).doesNotContainKeys(
                MetricRegistry.name("parsers", "stub", "sessionCount"),
                MetricRegistry.name("parsers", "stub", "templateCount"));
    }

    /**
     * #546, the defect the old register-if-absent guard caused: a parser restarted under the
     * same name registered nothing, so both gauges stayed bound to the previous instance's
     * session manager and reported its final counts forever. Here the second parser sees a
     * different number of exporters than the first, so a gauge still reading the dead one is
     * unambiguous.
     */
    @Test
    void gaugesFollowAParserRestartedUnderTheSameName() throws Exception {
        final var registry = new MetricRegistry();

        final var first = new StubParser(registry);
        first.start();
        parse(first, ADD_TEMPLATE, REMOTE);
        parse(first, ADD_TEMPLATE, SECOND_REMOTE);
        assertThat(gauge(registry, "sessionCount")).isEqualTo(2);
        first.stop();

        final var second = new StubParser(registry);
        second.start();
        try {
            parse(second, ADD_TEMPLATE, REMOTE);

            assertThat(gauge(registry, "sessionCount"))
                    .as("the gauge reads the live parser, not the one it replaced")
                    .isEqualTo(1);
        } finally {
            second.stop();
        }
    }

    /** stop() is idempotent, like the housekeeper and future handling it sits beside. */
    @Test
    void aSecondStopIsANoOp() throws Exception {
        final var registry = new MetricRegistry();
        final var parser = new StubParser(registry);
        parser.start();
        parser.stop();

        assertThatCode(parser::stop).doesNotThrowAnyException();
    }

    private static int gauge(final MetricRegistry registry, final String name) {
        final var gaugeMetric = registry.getGauges().get(MetricRegistry.name("parsers", "stub", name));
        assertThat(gaugeMetric).as("gauge parsers.stub.%s is registered", name).isNotNull();
        // the gauge yields null until start() builds the session manager — assert rather than
        // let an unboxing NPE stand in for a failure message
        final Object value = gaugeMetric.getValue();
        assertThat(value).as("gauge parsers.stub.%s has a value (parser started?)", name).isNotNull();
        return (Integer) value;
    }

    private void parse(final byte marker) throws Exception {
        parse(this.parser, marker, REMOTE);
    }

    private static void parse(final UdpParserBase parser, final byte marker, final InetSocketAddress remote)
            throws Exception {
        final ByteBuf buffer = Unpooled.buffer().writeByte(marker);
        try {
            parser.parse(Instant.now(), buffer, remote, LOCAL).join();
        } finally {
            buffer.release();
        }
    }

    private static final class StubParser extends UdpParserBase {

        private boolean templateResolved;
        private boolean garbageTemplateRetained;

        StubParser() {
            this(new MetricRegistry());
        }

        StubParser(final MetricRegistry metricRegistry) {
            super(Protocol.IPFIX, "stub", (source, flow) -> { }, new Identity("t", "o", "z", "s"),
                    metricRegistry);
        }

        @Override
        protected FlowPacket parse(final Session session, final ByteBuf buffer) throws Exception {
            switch (buffer.readByte()) {
                case ADD_TEMPLATE -> session.addTemplate(0,
                        Template.builder(TEMPLATE_ID, Template.Type.TEMPLATE)
                                .withFields(List.of(field())).build());
                case MALFORMED -> throw new InvalidPacketException(buffer, "Invalid set ID: %d", 0);
                case NEEDS_TEMPLATE -> {
                    session.getResolver(0).lookupTemplate(TEMPLATE_ID);
                    this.templateResolved = true;
                }
                case POISONED -> {
                    // A mis-framed packet: a garbage region parsed as a template set (installed
                    // into the session) before a later set fails the whole packet.
                    session.addTemplate(0, Template.builder(GARBAGE_TEMPLATE_ID, Template.Type.TEMPLATE)
                            .withFields(List.of(field())).build());
                    throw new InvalidPacketException(buffer, "Invalid set ID: %d", 0);
                }
                case ADD_SECOND_TEMPLATE -> session.addTemplate(0,
                        Template.builder(SECOND_TEMPLATE_ID, Template.Type.TEMPLATE)
                                .withFields(List.of(field())).build());
                case ADD_OTHER_DOMAIN -> session.addTemplate(OTHER_DOMAIN,
                        Template.builder(TEMPLATE_ID, Template.Type.TEMPLATE)
                                .withFields(List.of(field())).build());
                case PROBE_POISON -> {
                    try {
                        session.getResolver(0).lookupTemplate(GARBAGE_TEMPLATE_ID);
                        this.garbageTemplateRetained = true;
                    } catch (final org.riptide.flows.parser.exceptions.MissingTemplateException expected) {
                        this.garbageTemplateRetained = false;
                    }
                }
                default -> throw new IllegalStateException("unexpected marker");
            }
            return packet();
        }

        @Override
        protected UdpSessionManager.SessionKey buildSessionKey(final InetSocketAddress remoteAddress,
                                                               final InetSocketAddress localAddress) {
            return new Netflow9UdpParser.HostSessionKey(remoteAddress.getAddress(), localAddress);
        }

        private static FlowPacket packet() {
            return new FlowPacket() {
                @Override
                public Stream<org.riptide.flows.parser.data.Flow> buildFlows(final Instant receivedAt) {
                    return Stream.empty();
                }

                @Override
                public long getObservationDomainId() {
                    return 0;
                }

                @Override
                public long getSequenceNumber() {
                    return 0;
                }
            };
        }

        private static Field field() {
            return new Field() {
                @Override
                public int length() {
                    return 0;
                }

                @Override
                public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                    return new StringValue("f", null, null, null);
                }
            };
        }
    }
}
