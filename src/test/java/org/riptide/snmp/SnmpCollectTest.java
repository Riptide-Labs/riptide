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
}
