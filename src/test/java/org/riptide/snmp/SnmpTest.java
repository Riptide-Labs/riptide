/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.codahale.metrics.MetricRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.slf4j.LoggerFactory;
import org.snmp4j.fluent.TargetBuilder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import inet.ipaddr.IPAddressString;

public class SnmpTest {

    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(12345);
    private static final SecretResolvers SECRET_RESOLVERS = SecretResolvers.defaults();
    private TestSnmpAgent currentAgent;

    @AfterEach
    public void cleanup() throws Exception {
        if (currentAgent != null) {
            currentAgent.stop();
            currentAgent = null;
        }
    }

    private int getNextPort() {
        return PORT_COUNTER.getAndIncrement();
    }

    public static SnmpEndpoint communityV1(final IPAddressString ipAddressString, final int port, final String community) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v1);
        snmpDefinition.setCommunity(SecretRef.of(community));
        snmpDefinition.setSecurityName(null);
        snmpDefinition.setAuthProtocol(null);
        snmpDefinition.setAuthPassphrase(null);
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint communityV2c(final IPAddressString ipAddressString, final int port,
                                            final String community, final java.time.Duration refreshInterval,
                                            final java.time.Duration snapshotExpiry) {
        final SnmpEndpoint endpoint = communityV2c(ipAddressString, port, community);
        return endpoint.withCadence(refreshInterval, snapshotExpiry);
    }

    public static SnmpEndpoint communityV2c(final IPAddressString ipAddressString, final int port, final String community) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v2c);
        snmpDefinition.setCommunity(SecretRef.of(community));
        snmpDefinition.setSecurityName(null);
        snmpDefinition.setAuthProtocol(null);
        snmpDefinition.setAuthPassphrase(null);
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint noAuthNoPriv(final IPAddressString ipAddressString, final int port, final String securityName) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
       snmpDefinition.setSnmpVersion(SnmpVersion.v3);
        snmpDefinition.setCommunity(null);
        snmpDefinition.setSecurityName(securityName);
        snmpDefinition.setAuthProtocol(null);
        snmpDefinition.setAuthPassphrase(null);
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint authNoPriv(final IPAddressString ipAddressString, final int port, final String securityName, final TargetBuilder.AuthProtocol authProtocol, final String authPassphrase) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v3);
        snmpDefinition.setCommunity(null);
        snmpDefinition.setSecurityName(securityName);
        snmpDefinition.setAuthProtocol(authProtocol);
        snmpDefinition.setAuthPassphrase(SecretRef.of(authPassphrase));
        snmpDefinition.setPrivProtocol(null);
        snmpDefinition.setPrivPassphrase(null);
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    public static SnmpEndpoint authPriv(final IPAddressString ipAddressString, final int port, final String securityName, final TargetBuilder.AuthProtocol authProtocol, final String authPassphrase, final TargetBuilder.PrivProtocol privProtocol, final String privPassphrase) {
        final SnmpDefinition snmpDefinition = new SnmpDefinition();
        snmpDefinition.setPort(port);
        snmpDefinition.setSnmpVersion(SnmpVersion.v3);
        snmpDefinition.setCommunity(null);
        snmpDefinition.setSecurityName(securityName);
        snmpDefinition.setAuthProtocol(authProtocol);
        snmpDefinition.setAuthPassphrase(SecretRef.of(authPassphrase));
        snmpDefinition.setPrivProtocol(privProtocol);
        snmpDefinition.setPrivPassphrase(SecretRef.of(privPassphrase));
        return snmpDefinition.createEndpoint(ipAddressString);
    }

    /**
     * The abandonment path (#536), end to end against a real agent through the real
     * listener wiring: a zero budget abandons before the request reaches the wire, rows
     * are discarded, and the outcome is ABANDONED — never TIMEOUT, which would let the
     * poller log that a responsive agent "did not answer".
     */
    @Test
    public void anExhaustedBudgetAbandonsTheWalkWithoutCachingPartialRows(@TempDir Path temporaryFolder)
            throws Exception {
        final int port = getNextPort();
        currentAgent = new TestSnmpAgent("127.0.0.1/" + port, temporaryFolder);
        currentAgent.start();
        currentAgent.registerIfTable();
        currentAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = communityV2c(new IPAddressString("127.0.0.1"), port, TestSnmpAgent.COMMUNITY);
        final var abandoned = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS, 0L);

        assertThat(abandoned.outcome()).isEqualTo(SnmpUtils.WalkOutcome.ABANDONED);
        Assertions.assertThat(abandoned.rows()).as("an incomplete table must not be cached as complete").isEmpty();

        // and the same agent with a real budget serves the full table: the abandonment was
        // the budget's doing, not the agent's
        final var full = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS);
        assertThat(full.outcome()).isEqualTo(SnmpUtils.WalkOutcome.OK);
        Assertions.assertThat(full.rows()).containsKey(1);
    }

    @Test
    public void testSnmpV2(@TempDir Path temporaryFolder) throws Exception {
        final int port = getNextPort();
        currentAgent = new TestSnmpAgent("127.0.0.1/" + port, temporaryFolder);
        currentAgent.start();
        currentAgent.registerIfTable();
        currentAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = communityV2c(new IPAddressString("127.0.0.1"), port, TestSnmpAgent.COMMUNITY);
        final Map<Integer, IfInfo> ifMap = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS).rows();

        assertThat(ifMap.get(1).name()).isEqualTo("eth0-x");
        assertThat(ifMap.get(1).alias()).isEqualTo("My ethernet interface");
        assertThat(ifMap.get(1).highSpeed()).isEqualTo(14L);
        assertThat(ifMap.get(2).name()).isEqualTo("lo0-x");
        assertThat(ifMap.get(2).alias()).isEqualTo("My loopback interface");
        assertThat(ifMap.get(2).highSpeed()).isEqualTo(34L);
    }

    @Test
    public void testSnmpV3_noAuthNoPriv(@TempDir Path temporaryFolder) throws Exception {
        final int port = getNextPort();
        currentAgent = new TestSnmpAgent("127.0.0.1/" + port, temporaryFolder);
        currentAgent.start();
        currentAgent.registerIfTable();
        currentAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = noAuthNoPriv(new IPAddressString("127.0.0.1"), port, TestSnmpAgent.NOAUTHNOPRIV_USERNAME);
        final Map<Integer, IfInfo> ifMap = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS).rows();

        assertThat(ifMap.get(1).name()).isEqualTo("eth0-x");
        assertThat(ifMap.get(1).alias()).isEqualTo("My ethernet interface");
        assertThat(ifMap.get(1).highSpeed()).isEqualTo(14L);
        assertThat(ifMap.get(2).name()).isEqualTo("lo0-x");
        assertThat(ifMap.get(2).alias()).isEqualTo("My loopback interface");
        assertThat(ifMap.get(2).highSpeed()).isEqualTo(34L);
    }

    @Test
    public void testIfTableFallback(@TempDir Path temporaryFolder) throws Exception {
        final int port = getNextPort();
        currentAgent = new TestSnmpAgent("127.0.0.1/" + port, temporaryFolder);
        currentAgent.start();
        currentAgent.registerIfTable();

        final SnmpEndpoint snmpEndpoint = noAuthNoPriv(new IPAddressString("127.0.0.1"), port, TestSnmpAgent.NOAUTHNOPRIV_USERNAME);
        final Map<Integer, IfInfo> ifMap = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS).rows();

        assertThat(ifMap.get(1).name()).isEqualTo("eth0");
        assertThat(ifMap.get(1).alias()).isNull();
        assertThat(ifMap.get(1).highSpeed()).isNull();
        assertThat(ifMap.get(2).name()).isEqualTo("lo0");
    }

    @Test
    public void testSnmpV3_authNoPriv(@TempDir Path temporaryFolder) throws Exception {
        final int port = getNextPort();
        currentAgent = new TestSnmpAgent("127.0.0.1/" + port, temporaryFolder);
        currentAgent.start();
        currentAgent.registerIfTable();
        currentAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = authNoPriv(new IPAddressString("127.0.0.1"), port, TestSnmpAgent.AUTHNOPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1, TestSnmpAgent.AUTHNOPRIV_AUTH_PASSHRASE);
        final Map<Integer, IfInfo> ifMap = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS).rows();

        assertThat(ifMap.get(1).name()).isEqualTo("eth0-x");
        assertThat(ifMap.get(1).alias()).isEqualTo("My ethernet interface");
        assertThat(ifMap.get(1).highSpeed()).isEqualTo(14L);
        assertThat(ifMap.get(2).name()).isEqualTo("lo0-x");
        assertThat(ifMap.get(2).alias()).isEqualTo("My loopback interface");
        assertThat(ifMap.get(2).highSpeed()).isEqualTo(34L);
    }

    @Test
    public void testSnmpV3_authPriv(@TempDir Path temporaryFolder) throws Exception {
        final int port = getNextPort();
        currentAgent = new TestSnmpAgent("127.0.0.1/" + port, temporaryFolder);
        currentAgent.start();
        currentAgent.registerIfTable();
        currentAgent.registerIfXTable();

        final SnmpEndpoint snmpEndpoint = authPriv(new IPAddressString("127.0.0.1"), port, TestSnmpAgent.AUTHPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1, TestSnmpAgent.AUTHPRIV_AUTH_PASSHRASE, TargetBuilder.PrivProtocol.aes128, TestSnmpAgent.AUTHPRIV_PRIV_PASSHRASE);
        final Map<Integer, IfInfo> ifMap = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS).rows();

        assertThat(ifMap.get(1).name()).isEqualTo("eth0-x");
        assertThat(ifMap.get(1).alias()).isEqualTo("My ethernet interface");
        assertThat(ifMap.get(1).highSpeed()).isEqualTo(14L);
        assertThat(ifMap.get(2).name()).isEqualTo("lo0-x");
        assertThat(ifMap.get(2).alias()).isEqualTo("My loopback interface");
        assertThat(ifMap.get(2).highSpeed()).isEqualTo(34L);
    }

    @Test
    public void walkErrorLogsEndpointIdentityWithoutCommunity(@TempDir Path temporaryFolder) throws Exception {
        final int port = getNextPort();
        currentAgent = new TestSnmpAgent("127.0.0.1/" + port, temporaryFolder);
        currentAgent.start();
        currentAgent.registerIfTable();
        currentAgent.registerIfXTable();

        // A mismatched community is silently dropped by the agent, so the walk times out and takes
        // the error path that logs the target — which must not render the credential (#335).
        final String wrongCommunity = "wr0ngS3cretCommunity";
        final SnmpEndpoint snmpEndpoint = communityV2c(new IPAddressString("127.0.0.1"), port, wrongCommunity);
        snmpEndpoint.getSnmpDefinition().setTimeout(50);

        final var logger = (Logger) LoggerFactory.getLogger(SnmpUtils.class);
        final var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            Assertions.assertThat(SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS).rows()).isEmpty();

            // The leak lives in argument rendering, so assert on the formatted messages
            Assertions.assertThat(appender.list)
                    .isNotEmpty()
                    .noneMatch(event -> event.getFormattedMessage().contains(wrongCommunity))
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("Error querying")
                                .contains("127.0.0.1")
                                .contains("(v2c)");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    public void timedOutWalkSkipsTheIfTableFallback() throws Exception {
        // no agent listening: the ifXTable walk times out; the ifTable fallback would only time
        // out again, so exactly one walk (and one WARN) must happen (#337)
        final SnmpEndpoint snmpEndpoint = communityV2c(new IPAddressString("127.0.0.1"), getNextPort(), TestSnmpAgent.COMMUNITY);
        snmpEndpoint.getSnmpDefinition().setTimeout(50);

        final var logger = (Logger) LoggerFactory.getLogger(SnmpUtils.class);
        final var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            final var walk = SnmpUtils.getIfInfoMap(snmpEndpoint, SECRET_RESOLVERS);

            assertThat(walk.outcome()).isEqualTo(SnmpUtils.WalkOutcome.TIMEOUT);
            Assertions.assertThat(walk.rows()).isEmpty();
            Assertions.assertThat(appender.list)
                    .filteredOn(event -> event.getFormattedMessage().contains("Error querying"))
                    .hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    public void fallbackRunsOnErrorAndEmptyButNotOnTimeout() {
        // v1 agents lacking ifXTable answer noSuchName (ERROR) — the fallback exists for them
        assertThat(SnmpUtils.shouldFallback(new SnmpUtils.WalkResult(Map.of(), SnmpUtils.WalkOutcome.ERROR))).isTrue();
        // v2c/v3 agents lacking ifXTable answer clean-empty
        assertThat(SnmpUtils.shouldFallback(new SnmpUtils.WalkResult(Map.of(), SnmpUtils.WalkOutcome.OK))).isTrue();
        assertThat(SnmpUtils.shouldFallback(new SnmpUtils.WalkResult(Map.of(1, new IfInfo("eth0", null, null)), SnmpUtils.WalkOutcome.OK))).isFalse();
        assertThat(SnmpUtils.shouldFallback(new SnmpUtils.WalkResult(Map.of(), SnmpUtils.WalkOutcome.TIMEOUT))).isFalse();
    }

    @Test
    public void unresolvableSecretRefDegradesToEmptyInsteadOfThrowing() {
        // a broken secret reference must yield an unenriched flow, never fail the pipeline
        final SnmpService snmpService = new DefaultSnmpService(SECRET_RESOLVERS, new MetricRegistry());
        final SnmpEndpoint snmpEndpoint = communityV2c(new IPAddressString("127.0.0.1"), getNextPort(), "env://RIPTIDE_TEST_MISSING_VAR");

        assertThat(snmpService.getIfInfo(snmpEndpoint, 1)).isInstanceOf(Optional.class).isEmpty();
    }




}
