package org.riptide.flows.parser.session;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ipfix.IpfixUdpParser;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;

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
}
