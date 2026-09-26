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
import org.snmp4j.fluent.TargetBuilder;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

import java.nio.file.Path;
import java.time.Duration;

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
     * table — untested here, covered by {@code shouldFallback}'s existing v1 coverage.
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
     * survive being driven through {@code DefaultSnmpService}'s one-session-per-version session,
     * and a second collect against the same endpoint must succeed without rediscovering the
     * engine ID (snmp4j caches it per address; the session stays open between calls).
     */
    @Test
    void v3CollectWorksOnTheSharedSessionAndReusesTheCachedEngineId() {
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
        assertThat(this.service.openSessions()).as("v3 shares one session; the engine ID is cached, not "
                + "rediscovered").isEqualTo(1);
    }

    @Test
    void numberTreatsANonNumericCellAsAbsentRatherThanThrowing() {
        final VariableBinding nonNumeric = new VariableBinding(new OID("1.3.6.1.2.1.31.1.1.1.6.1"),
                new OctetString("not a counter"));

        assertThat(SnmpUtils.number(nonNumeric)).isNull();
    }
}
