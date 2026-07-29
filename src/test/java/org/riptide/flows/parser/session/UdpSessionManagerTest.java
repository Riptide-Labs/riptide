/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ipfix.IpfixUdpParser;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class UdpSessionManagerTest {

    private final InetSocketAddress localAddress1 = new InetSocketAddress("10.10.10.10", 10001);
    private final InetSocketAddress localAddress2 = new InetSocketAddress("10.10.10.10", 10002);

    private final long observationId1 = 11111;

    private final InetSocketAddress remoteAddress1 = new InetSocketAddress("10.10.10.20", 51001);
    private final InetSocketAddress remoteAddress2 = new InetSocketAddress("10.10.10.20", 51002);

    private final long observationId2 = 22222;

    private final InetSocketAddress remoteAddress3 = new InetSocketAddress("10.10.10.30", 51001);
    private final InetSocketAddress remoteAddress4 = new InetSocketAddress("10.10.10.30", 51002);

    private final int templateId1 = 100;

    private Scope scope(final String name) {
        return new Scope() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int length() {
                return 0;
            }

            @Override
            public Value<?> parse(Session.Resolver resolver, ByteBuf buffer) {
                return new StringValue(name, null, null, null);
            }
        };
    }

    private Field field(final String name) {
        return new Field() {
            @Override
            public int length() {
                return 0;
            }

            @Override
            public Value<?> parse(Session.Resolver resolver, ByteBuf buffer) {
                return new StringValue(name, null, null, null);
            }
        };
    }

    private Value<?> value(String name, String value) {
        return new StringValue(name, value);
    }

    private void testNetflow9SessionKeys(final InetSocketAddress remote1, final InetSocketAddress local1, final InetSocketAddress remote2, final InetSocketAddress local2, final boolean shouldMatch) {
        final var sessionKey1 = new Netflow9UdpParser.SessionKey(remote1.getAddress(), local1);
        final var sessionKey2 = new Netflow9UdpParser.SessionKey(remote2.getAddress(), local2);
        testSessionKeys(sessionKey1, sessionKey2, shouldMatch);
    }

    private void testIpFixSessionKeys(final InetSocketAddress remote1, final InetSocketAddress local1, final InetSocketAddress remote2, final InetSocketAddress local2, final boolean shouldMatch) {
        final var sessionKey1 = new IpfixUdpParser.SessionKey(remote1, local1);
        final var sessionKey2 = new IpfixUdpParser.SessionKey(remote2, local2);
        testSessionKeys(sessionKey1, sessionKey2, shouldMatch);
    }

    private void testSessionKeys(final UdpSessionManager.SessionKey sessionKey1, final UdpSessionManager.SessionKey sessionKey2, final boolean shouldMatch) {
        final var udpSessionManager = new UdpSessionManager(Duration.ofMinutes(30), () -> new SequenceNumberTracker(32));
        final var session1 = udpSessionManager.getSession(sessionKey1);

        final var scopes = new ArrayList<Scope>();
        scopes.add(scope("scope1"));
        scopes.add(scope("scope2"));

        final var fields = new ArrayList<Field>();
        fields.add(field("field1"));
        fields.add(field("field2"));

        final var template = Template.builder(100, Template.Type.OPTIONS_TEMPLATE).withFields(fields).withScopes(scopes).build();
        session1.addTemplate(observationId1, template);

        final var scopesValue = new ArrayList<Value<?>>();
        scopesValue.add(value("scope1", "scopeValue1"));
        scopesValue.add(value("scope2", "scopeValue2"));

        final var fieldsValue = new ArrayList<Value<?>>();
        fieldsValue.add(value("additionalField1", "additionalValue1"));
        fieldsValue.add(value("additionalField2", "additionalValue2"));

        session1.addOptions(observationId1, templateId1, scopesValue, fieldsValue);

        final Session session2 = udpSessionManager.getSession(sessionKey2);

        final List<Value<?>> notMatchingValues = new ArrayList<>();
        notMatchingValues.add(value("scope1", "scopeValue1"));
        notMatchingValues.add(value("scope2", "mismatch"));

        final List<Value<?>> matchingValues = new ArrayList<>();
        matchingValues.add(value("scope1", "scopeValue1"));
        matchingValues.add(value("scope2", "scopeValue2"));

        assertThat(session2.getResolver(observationId1).lookupOptions(notMatchingValues)).isEmpty();
        assertThat(session2.getResolver(observationId2).lookupOptions(matchingValues)).isEmpty();

        final List<Value<?>> result = session2.getResolver(observationId1).lookupOptions(matchingValues);

        System.out.println("Checking session keys " + sessionKey1 + " and " + sessionKey2);
        assertThat(result).hasSize(shouldMatch ? 2 : 0);
        assertThat(result.contains(new StringValue("additionalField1", "additionalValue1"))).isEqualTo(shouldMatch);
        assertThat(result.contains(new StringValue("additionalField2", "additionalValue2"))).isEqualTo(shouldMatch);
    }

    /**
     * see NMS-13539
     */
    @Test
    public void optionsRemovalTest() {
        final var sessionKey = new Netflow9UdpParser.SessionKey(remoteAddress1.getAddress(), localAddress1);

        final var udpSessionManager = new UdpSessionManager(Duration.ofMinutes(0), () -> new SequenceNumberTracker(32));
        final var session = udpSessionManager.getSession(sessionKey);

        final var scopes = new ArrayList<Scope>();
        scopes.add(scope("scope1"));
        scopes.add(scope("scope2"));

        final var fields = new ArrayList<Field>();
        fields.add(field("field1"));
        fields.add(field("field2"));

        final var template = Template.builder(100, Template.Type.OPTIONS_TEMPLATE).withFields(fields).withScopes(scopes).build();
        session.addTemplate(observationId1, template);

        final var scopesValue = new ArrayList<Value<?>>();
        scopesValue.add(value("scope1", "scopeValue1"));
        scopesValue.add(value("scope2", "scopeValue2"));

        final var fieldsValue = new ArrayList<Value<?>>();
        fieldsValue.add(value("additionalField1", "additionalValue1"));
        fieldsValue.add(value("additionalField2", "additionalValue2"));

        session.addOptions(observationId1, templateId1, scopesValue, fieldsValue);

        assertThat(udpSessionManager.getTemplates().keySet()).contains(new UdpSessionManager.TemplateKey(sessionKey, observationId1, template.id));
        assertThat(udpSessionManager.getTemplates().get(new UdpSessionManager.TemplateKey(sessionKey, observationId1, template.id)).wrapped.options.entrySet()).isNotEmpty();

        udpSessionManager.doHousekeeping();

        assertThat(udpSessionManager.getTemplates().keySet()).doesNotContain(new UdpSessionManager.TemplateKey(sessionKey, observationId1, template.id));
        assertThat(udpSessionManager.getTemplates().get(new UdpSessionManager.TemplateKey(sessionKey, observationId1, template.id))).isNull();
    }

    /**
     * Option resolution must depend only on the exporter in hand, not on how many other exporters
     * the collector is fronting. Guards the per-exporter template index (#389): the previous flat
     * map found an exporter's templates by scanning every entry in the collector, so this asserts
     * the property that indexing has to preserve — same answer with 1 exporter or 500.
     */
    @Test
    public void lookupOptionsIsUnaffectedByOtherExporters() {
        final var sessionKey = new Netflow9UdpParser.SessionKey(remoteAddress1.getAddress(), localAddress1);
        final var manager = new UdpSessionManager(Duration.ofMinutes(5), () -> new SequenceNumberTracker(32));
        final var session = manager.getSession(sessionKey);

        final var scopes = List.of(scope("scope1"), scope("scope2"));
        final var fields = List.of(field("field1"), field("field2"));
        session.addTemplate(observationId1,
                Template.builder(templateId1, Template.Type.OPTIONS_TEMPLATE)
                        .withFields(new ArrayList<>(fields)).withScopes(new ArrayList<>(scopes)).build());

        final var scopeValues = new ArrayList<Value<?>>(List.of(
                value("scope1", "scopeValue1"), value("scope2", "scopeValue2")));
        session.addOptions(observationId1, templateId1, scopeValues, new ArrayList<>(List.of(
                value("additionalField1", "additionalValue1"), value("additionalField2", "additionalValue2"))));

        final var expected = session.getResolver(observationId1).lookupOptions(scopeValues);
        assertThat(expected).hasSize(2);

        // 500 unrelated exporters, each with an option template carrying the same template id and
        // the same scope names but different values — the shape most likely to leak across
        // exporters if the index were keyed wrongly.
        for (int i = 0; i < 500; i++) {
            final var otherKey = new Netflow9UdpParser.SessionKey(
                    InetAddress.getLoopbackAddress(), new InetSocketAddress("10.10.10.10", 20000 + i));
            final var other = manager.getSession(otherKey);
            other.addTemplate(observationId1,
                    Template.builder(templateId1, Template.Type.OPTIONS_TEMPLATE)
                            .withFields(new ArrayList<>(fields)).withScopes(new ArrayList<>(scopes)).build());
            other.addOptions(observationId1, templateId1, scopeValues,
                    new ArrayList<>(List.of(value("additionalField1", "WRONG-" + i))));
        }

        assertThat(manager.domainCount()).isEqualTo(501);

        // unchanged answer, and specifically none of the other exporters' values
        assertThat(session.getResolver(observationId1).lookupOptions(scopeValues))
                .containsExactlyElementsOf(expected);
        assertThat(session.getResolver(observationId1).lookupOptions(scopeValues))
                .noneMatch(v -> String.valueOf(v.getValue()).startsWith("WRONG-"));
    }

    /**
     * Once an exporter's last template expires the exporter mapping itself must go, or the
     * per-exporter index accumulates one empty entry per address ever seen — the same
     * unbounded-growth failure the sequence-tracker eviction exists to prevent. Churning sources
     * (NAT, roaming agents) make this reachable in practice.
     */
    @Test
    public void housekeepingReapsExportersWithNoTemplatesLeft() {
        final var manager = new UdpSessionManager(Duration.ofMinutes(0), () -> new SequenceNumberTracker(32));

        for (int i = 0; i < 25; i++) {
            final var key = new Netflow9UdpParser.SessionKey(
                    InetAddress.getLoopbackAddress(), new InetSocketAddress("10.10.10.10", 30000 + i));
            manager.getSession(key).addTemplate(observationId1,
                    Template.builder(templateId1, Template.Type.TEMPLATE)
                            .withFields(new ArrayList<>(List.of(field("field1")))).build());
        }

        assertThat(manager.domainCount()).isEqualTo(25);
        assertThat(manager.count()).isEqualTo(25);

        manager.doHousekeeping();

        assertThat(manager.count()).as("templates expire").isZero();
        assertThat(manager.domainCount()).as("and the exporter mappings are reaped with them").isZero();
    }

    @Test
    public void testNetflow9() {
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress1, localAddress1, true);
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress1, localAddress2, false);
        // this should match, since Netflow v9 session keys do not include the remote port, see NMS-10721
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress2, localAddress1, true);
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress2, localAddress2, false);
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress3, localAddress1, false);
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress3, localAddress2, false);
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress4, localAddress1, false);
        testNetflow9SessionKeys(remoteAddress1, localAddress1, remoteAddress4, localAddress2, false);
    }

    @Test
    public void testIpFix() {
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress1, localAddress1, true);
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress1, localAddress2, false);
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress2, localAddress1, false);
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress2, localAddress2, false);
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress3, localAddress1, false);
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress3, localAddress2, false);
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress4, localAddress1, false);
        testIpFixSessionKeys(remoteAddress1, localAddress1, remoteAddress4, localAddress2, false);
    }

    @Test
    public void sequenceStreamsAreScopedByExporterIdentity() throws Exception {
        // two sFlow agents behind ONE UDP source (relay/NAT/shared socket), both
        // sub_agent_id 0: their independent sequence streams must not interleave in
        // a single tracker (which flags spurious errors for gaps within patience)
        final var sessionKey = new Netflow9UdpParser.SessionKey(remoteAddress1.getAddress(), localAddress1);
        final var manager = new UdpSessionManager(Duration.ofMinutes(30), () -> new SequenceNumberTracker(32));
        final var session = manager.getSession(sessionKey);

        final var agentA = new ExporterIdentity.Sflow(InetAddress.getByName("10.0.0.1"), 0);
        final var agentB = new ExporterIdentity.Sflow(InetAddress.getByName("10.0.0.2"), 0);

        assertThat(session.verifySequenceNumber(agentA, 1, 1)).isTrue();
        assertThat(session.verifySequenceNumber(agentB, 20, 1)).isTrue();
        assertThat(session.verifySequenceNumber(agentA, 2, 1)).isTrue();
        assertThat(session.verifySequenceNumber(agentB, 21, 1)).isTrue();
        assertThat(manager.sequenceTrackerCount()).isEqualTo(2);
    }

    @Test
    public void housekeepingEvictsSequenceTrackers() throws Exception {
        final var sessionKey = new Netflow9UdpParser.SessionKey(remoteAddress1.getAddress(), localAddress1);
        final var manager = new UdpSessionManager(Duration.ofMinutes(0), () -> new SequenceNumberTracker(32));
        final var identity = new ExporterIdentity.Sflow(InetAddress.getByName("10.0.0.1"), 0);

        manager.getSession(sessionKey).verifySequenceNumber(identity, 1, 1);
        assertThat(manager.sequenceTrackerCount()).isEqualTo(1);
        manager.doHousekeeping();
        assertThat(manager.sequenceTrackerCount()).isZero();
    }

    @Test
    public void addOptionsNotifiesTheOptionListener() throws Exception {
        final var sessionKey = new Netflow9UdpParser.SessionKey(remoteAddress1.getAddress(), localAddress1);
        final var seen = new ArrayList<ExporterIdentity>();
        final var manager = new UdpSessionManager(Duration.ofMinutes(30), () -> new SequenceNumberTracker(32),
                (identity, scopes, values) -> seen.add(identity));
        final var session = manager.getSession(sessionKey);

        final var template = Template.builder(templateId1, Template.Type.OPTIONS_TEMPLATE)
                .withFields(List.of(field("f"))).withScopes(List.of(scope("s"))).build();
        session.addTemplate(observationId1, template);
        session.addOptions(observationId1, templateId1, List.of(value("s", "sv")), List.of(value("f", "fv")));

        assertThat(seen).containsExactly(
                new ExporterIdentity.NetflowIpfix(remoteAddress1.getAddress(), observationId1));
    }
}
