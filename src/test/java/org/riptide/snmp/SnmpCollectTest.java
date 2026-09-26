/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.secrets.SecretResolvers;
import org.riptide.snmp.collect.CollectedTable;
import org.riptide.snmp.collect.CollectionDefinitions;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.fluent.TargetBuilder;
import org.snmp4j.mp.MPv3;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import inet.ipaddr.IPAddressString;

import static org.assertj.core.api.Assertions.assertThat;

class SnmpCollectTest {

    private static final int PORT = 12400;
    private TestSnmpAgent agent;
    private DefaultSnmpService service;

    @BeforeEach
    void start(@TempDir final Path dir) throws Exception {
        this.agent = new TestSnmpAgent("127.0.0.1/" + PORT, dir);
        this.agent.start();
        this.agent.registerIfTable();
        this.agent.registerIfXTable();
        this.service = new DefaultSnmpService(SecretResolvers.defaults(), new com.codahale.metrics.MetricRegistry());
    }

    @AfterEach
    void stop() {
        this.service.close();
        this.agent.stop();
    }

    @Test
    void collectReadsCountersAndInfoForEveryRowAndJoinsBothTables() {
        final SnmpEndpoint endpoint = SnmpTest.communityV2c(new IPAddressString("127.0.0.1"), PORT, TestSnmpAgent.COMMUNITY);
        this.agent.setCounter(1, 6, 123_456_789_012L);

        final CollectedTable table = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10));

        assertThat(table.walkFailed()).isFalse();
        assertThat(table.rows()).containsKeys(1, 2);
        final var eth0 = table.rows().get(1);
        assertThat(eth0.values()).containsEntry("ifHCInOctets", 123_456_789_012L).containsKey("ifOperStatus")
                .containsKey("ifInErrors");
        assertThat(eth0.info()).containsEntry("ifName", "eth0-x").containsEntry("ifHighSpeed", "14");
    }

    @Test
    void twoCollectsReuseTheSameSessionAndSeeAMovedCounter() {
        final SnmpEndpoint endpoint = SnmpTest.communityV2c(new IPAddressString("127.0.0.1"), PORT, TestSnmpAgent.COMMUNITY);
        this.agent.setCounter(1, 6, 100L);
        final long before = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10)).rows().get(1).values().get("ifHCInOctets");
        this.agent.setCounter(1, 6, 250L);
        final long after = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10)).rows().get(1).values().get("ifHCInOctets");

        assertThat(before).isEqualTo(100L);
        assertThat(after).isEqualTo(250L);
        assertThat(this.service.openSessions()).as("one shared session per SNMP version").isEqualTo(1);
    }

    @Test
    void anUnreachablePortIsAFailedTableNotAnException() {
        final SnmpEndpoint endpoint = SnmpTest.communityV2c(new IPAddressString("127.0.0.1"), PORT + 1, TestSnmpAgent.COMMUNITY);
        final CollectedTable table = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(3));
        assertThat(table.walkFailed()).isTrue();
        assertThat(table.rows()).isEmpty();
    }

    /**
     * A device without ifXTable is not a failed collect on v2c/v3: the agent answers a clean,
     * empty walk for it, so ifTable-only columns still populate and only the ifXTable-only ones
     * (ifName, every HC octet counter) are absent. Only v1's noSuchName error fails the whole
     * table. That case is not tested here. {@code shouldFallback}'s existing v1 coverage pins it.
     */
    @Test
    void aDeviceWithoutIfXTableIsNotAFailedCollectOnV2c(@TempDir final Path dir) throws Exception {
        final int port = PORT + 2;
        final TestSnmpAgent bareAgent = new TestSnmpAgent("127.0.0.1/" + port, dir);
        bareAgent.start();
        bareAgent.registerIfTable();
        try {
            final SnmpEndpoint endpoint = SnmpTest.communityV2c(new IPAddressString("127.0.0.1"), port,
                    TestSnmpAgent.COMMUNITY);

            final CollectedTable table = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                    Duration.ofSeconds(10));

            assertThat(table.walkFailed()).isFalse();
            final var eth0 = table.rows().get(1);
            assertThat(eth0.values()).containsKey("ifOperStatus");
            assertThat(eth0.values()).doesNotContainKey("ifHCInOctets");
            assertThat(eth0.info()).doesNotContainKey("ifName");
        } finally {
            bareAgent.stop();
        }
    }

    /**
     * v3 collect on the shared session: engine-ID discovery and USM user registration must
     * survive being driven through {@code DefaultSnmpService}'s one-session-per-version session.
     * A second collect against the same endpoint must succeed on that same session. That the
     * second walk skips discovery is pinned by
     * {@code v3TargetDiscoversTheEngineIdOnlyWhileTheSessionHasNoneCached}.
     */
    @Test
    void v3CollectWorksOnTheSharedSessionTwice() {
        final SnmpEndpoint endpoint = SnmpTest.authPriv(new IPAddressString("127.0.0.1"), PORT,
                TestSnmpAgent.AUTHPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1,
                TestSnmpAgent.AUTHPRIV_AUTH_PASSHRASE, TargetBuilder.PrivProtocol.aes128,
                TestSnmpAgent.AUTHPRIV_PRIV_PASSHRASE);
        this.agent.setCounter(1, 6, 123_456_789_012L);

        final CollectedTable first = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10));
        assertThat(first.walkFailed()).isFalse();
        assertThat(first.rows()).containsKeys(1, 2);
        final var eth0 = first.rows().get(1);
        assertThat(eth0.values()).containsEntry("ifHCInOctets", 123_456_789_012L).containsKey("ifOperStatus")
                .containsKey("ifInErrors");
        assertThat(eth0.info()).containsEntry("ifName", "eth0-x").containsEntry("ifHighSpeed", "14");

        final CollectedTable second = this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10));
        assertThat(second.walkFailed()).isFalse();
        assertThat(this.service.openSessions()).as("v3 shares one session").isEqualTo(1);
    }

    /**
     * Discovery evicts snmp4j's cached engine ID and blocks the calling walk-io thread on a
     * synchronous request, so it must run only while the session caches no engine ID, not once
     * per walk. The counting session shares the real session's dispatcher, so the discovery it
     * counts is a real exchange with the agent.
     */
    @Test
    void v3TargetDiscoversTheEngineIdOnlyWhileTheSessionHasNoneCached() throws Exception {
        final SnmpEndpoint endpoint = SnmpTest.authPriv(new IPAddressString("127.0.0.1"), PORT,
                TestSnmpAgent.AUTHPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1,
                TestSnmpAgent.AUTHPRIV_AUTH_PASSHRASE, TargetBuilder.PrivProtocol.aes128,
                TestSnmpAgent.AUTHPRIV_PRIV_PASSHRASE);
        final SnmpBuilder builder = SnmpVersion.v3.getSnmpBuilder();
        final Snmp session = builder.build();
        final AtomicInteger discoveries = new AtomicInteger();
        final Snmp counting = new Snmp(session.getMessageDispatcher()) {
            @Override
            public <A extends Address> byte[] discoverAuthoritativeEngineID(final A address, final long timeout) {
                discoveries.incrementAndGet();
                return super.discoverAuthoritativeEngineID(address, timeout);
            }
        };
        try {
            final Target<?> first = SnmpVersion.v3.getTarget(counting, builder, endpoint, SecretResolvers.defaults());
            final Target<?> second = SnmpVersion.v3.getTarget(counting, builder, endpoint, SecretResolvers.defaults());

            assertThat(discoveries).as("discovered once, then served from the MPv3 cache").hasValue(1);
            assertThat(((UserTarget<?>) first).getAuthoritativeEngineID()).isNotEmpty();
            assertThat(((UserTarget<?>) second).getAuthoritativeEngineID())
                    .isEqualTo(((UserTarget<?>) first).getAuthoritativeEngineID());
        } finally {
            session.close();
        }
    }

    /**
     * snmp4j keeps a cached engine ID even when the agent at that address now reports another,
     * so a long-lived session must evict it after a failed walk. The replacement agent below
     * listens on the same address with a freshly generated engine ID, as a replaced or
     * re-imaged device would.
     */
    @Test
    void aFailedV3CollectEvictsTheEngineIdSoAReplacedAgentIsRediscovered(@TempDir final Path dir)
            throws Exception {
        final SnmpEndpoint endpoint = SnmpTest.authPriv(new IPAddressString("127.0.0.1"), PORT,
                TestSnmpAgent.AUTHPRIV_USERNAME, TargetBuilder.AuthProtocol.sha1,
                TestSnmpAgent.AUTHPRIV_AUTH_PASSHRASE, TargetBuilder.PrivProtocol.aes128,
                TestSnmpAgent.AUTHPRIV_PRIV_PASSHRASE);
        final Address address = SnmpVersion.v3.getTargetAddress(endpoint);
        assertThat(this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10)).walkFailed()).isFalse();
        final OctetString before = mpv3().getEngineID(address);
        assertThat(before).as("cached by the first collect").isNotNull();

        this.agent.stop();
        assertThat(this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(3)).walkFailed()).isTrue();
        assertThat(mpv3().getEngineID(address)).as("evicted after the failed collect").isNull();

        this.agent = new TestSnmpAgent("127.0.0.1/" + PORT, dir);
        this.agent.start();
        this.agent.registerIfTable();
        this.agent.registerIfXTable();
        assertThat(this.service.collect(endpoint, CollectionDefinitions.IF_MIB_INTERFACES,
                Duration.ofSeconds(10)).walkFailed()).as("the replacement agent is walked").isFalse();
        assertThat(mpv3().getEngineID(address)).as("rediscovered from the replacement agent")
                .isNotNull().isNotEqualTo(before);
    }

    private MPv3 mpv3() {
        return (MPv3) this.service.openSession(SnmpVersion.v3).getMessageProcessingModel(MPv3.ID);
    }

    @Test
    void numberTreatsANonNumericCellAsAbsentRatherThanThrowing() {
        final VariableBinding nonNumeric = new VariableBinding(new OID("1.3.6.1.2.1.31.1.1.1.6.1"),
                new OctetString("not a counter"));

        assertThat(SnmpUtils.number(nonNumeric)).isNull();
    }
}
