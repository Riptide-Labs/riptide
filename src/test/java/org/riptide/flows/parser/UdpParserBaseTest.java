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
    private static final InetSocketAddress LOCAL = new InetSocketAddress("10.0.0.2", 4739);
    private static final int TEMPLATE_ID = 256;

    /** T = adds a template; X = malformed (throws); D = requires the template to resolve. */
    private static final byte ADD_TEMPLATE = 'T';
    private static final byte MALFORMED = 'X';
    private static final byte NEEDS_TEMPLATE = 'D';
    /** P = installs a garbage template mid-packet, then fails; Q = probes for that template. */
    private static final byte POISONED = 'P';
    private static final byte PROBE_POISON = 'Q';
    private static final int GARBAGE_TEMPLATE_ID = 300;

    private ScheduledExecutorService executor;
    private StubParser parser;

    @BeforeEach
    void setUp() {
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.parser = new StubParser();
        this.parser.start(this.executor);
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

    private void parse(final byte marker) throws Exception {
        final ByteBuf buffer = Unpooled.buffer().writeByte(marker);
        try {
            this.parser.parse(Instant.now(), buffer, REMOTE, LOCAL).join();
        } finally {
            buffer.release();
        }
    }

    private static final class StubParser extends UdpParserBase {

        private boolean templateResolved;
        private boolean garbageTemplateRetained;

        StubParser() {
            super(Protocol.IPFIX, "stub", (source, flow) -> { }, new Identity("t", "o", "z", "s"),
                    new MetricRegistry());
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
            return new Netflow9UdpParser.SessionKey(remoteAddress.getAddress(), localAddress);
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
