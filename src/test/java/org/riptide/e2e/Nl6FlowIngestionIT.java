/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.time.Instant;

import static org.riptide.e2e.E2eTestSupport.await;
import static org.riptide.e2e.E2eTestSupport.freeUdpPort;

/**
 * End-to-end: nl6-simulated devices export NetFlow v5 / v9, IPFIX, and sFlow v5 over real
 * UDP into riptide's listeners (running in this JVM), through parsing,
 * enrichment, and classification, into a real ClickHouse. Assertions
 * reconcile ClickHouse row counts against nl6's per-collector ledger.
 */
@SpringBootTest
@ActiveProfiles("e2e")
public class Nl6FlowIngestionIT {

    /** Minimum ledger records per protocol before reconciliation starts. */
    private static final long MIN_RECORDS = 50;
    /** Tolerance for v9/IPFIX records legitimately dropped before the first template. */
    private static final double TEMPLATE_EPSILON = 0.05;

    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:25.3")
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final Nl6Container NL6 = new Nl6Container();

    private static final int NETFLOW5_PORT = freeUdpPort();
    private static final int NETFLOW9_PORT = freeUdpPort();
    private static final int IPFIX_PORT = freeUdpPort();
    private static final int SFLOW_PORT = freeUdpPort();

    private static final Instant TEST_START = Instant.now();

    private static Client queryClient;

    static {
        // Containers must be up before the Spring context binds properties.
        CLICKHOUSE.start();
        NL6.start();
    }

    @DynamicPropertySource
    static void riptideProperties(final DynamicPropertyRegistry registry) {
        registry.add("riptide.clickhouse.endpoint",
                () -> "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        registry.add("riptide.clickhouse.username", () -> "riptide");
        registry.add("riptide.clickhouse.password", () -> "riptide");

        registry.add("riptide.receivers.nf5.type", () -> "netflow5");
        registry.add("riptide.receivers.nf5.host", () -> "0.0.0.0");
        registry.add("riptide.receivers.nf5.port", () -> NETFLOW5_PORT);

        registry.add("riptide.receivers.nf9.type", () -> "netflow9");
        registry.add("riptide.receivers.nf9.host", () -> "0.0.0.0");
        registry.add("riptide.receivers.nf9.port", () -> NETFLOW9_PORT);

        registry.add("riptide.receivers.ipfix.type", () -> "ipfix");
        registry.add("riptide.receivers.ipfix.host", () -> "0.0.0.0");
        registry.add("riptide.receivers.ipfix.port", () -> IPFIX_PORT);

        registry.add("riptide.receivers.sf.type", () -> "sflow");
        registry.add("riptide.receivers.sf.host", () -> "0.0.0.0");
        registry.add("riptide.receivers.sf.port", () -> SFLOW_PORT);

        // A node matching the sFlow devices' agent addresses (payload-derived; the
        // shared-socket UDP source is the nl6 container, which this subnet does NOT
        // cover) with static interface names — enrichment proves node attribution.
        registry.add("riptide.nodes.sflow-agents.subnet-address", () -> "10.42.3.0/24");
        registry.add("riptide.nodes.sflow-agents.interfaces.1.name", () -> "sfl-in");
        registry.add("riptide.nodes.sflow-agents.interfaces.2.name", () -> "sfl-out");
    }

    @BeforeAll
    static void startTraffic() throws Exception {
        queryClient = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername("riptide")
                .setPassword("riptide")
                .setDefaultDatabase("riptide")
                .build();

        NL6.createDevices("10.42.0.1", 3, "netflow5", NETFLOW5_PORT);
        NL6.createDevices("10.42.1.1", 3, "netflow9", NETFLOW9_PORT);
        NL6.createDevices("10.42.2.1", 3, "ipfix", IPFIX_PORT);
        NL6.createDevices("10.42.3.1", 3, "sflow", SFLOW_PORT);
    }

    @Test
    void verifyNetflow5ReachesClickhouse() throws Exception {
        reconcile("netflow5", "NetflowV5", 0.0);
    }

    @Test
    void verifyNetflow9ReachesClickhouse() throws Exception {
        reconcile("netflow9", "NetflowV9", TEMPLATE_EPSILON);
        verifyTimestampsSane("NetflowV9");
    }

    @Test
    void verifyIpfixReachesClickhouse() throws Exception {
        reconcile("ipfix", "IPFIX", TEMPLATE_EPSILON);
        verifyTimestampsSane("IPFIX");
    }

    /**
     * sFlow runs in nl6's shared-socket mode (the container default), so the UDP
     * source address is the nl6 container — NOT a device. The assertions prove
     * payload-derived attribution: persisted exporters are the in-datagram agent
     * addresses, and static interface enrichment fires on the node that only a
     * device agent address can match.
     */
    @Test
    void verifySflowReachesClickhouseAttributedByAgentAddress() throws Exception {
        reconcile("sflow", "SFLOW", 0.0);
        verifyTimestampsSane("SFLOW");

        final var exporters = queryClient.queryAll(
                "SELECT DISTINCT exporterAddr FROM flows WHERE flowProtocol = 'SFLOW'");
        Assertions.assertThat(exporters).isNotEmpty().allSatisfy(row ->
                Assertions.assertThat(row.getString("exporterAddr")).startsWith("10.42.3."));

        await(Duration.ofMinutes(1), "sFlow rows enriched via the agent-address-matched node",
                () -> queryClient.queryAll(
                        "SELECT count() AS c FROM flows WHERE flowProtocol = 'SFLOW' AND inputSnmpIfName = 'sfl-in'")
                        .getFirst().getLong("c") > 0);
    }

    /**
     * Waits until nl6's ledger reports at least MIN_RECORDS sent for the
     * protocol, snapshots the ledger, then polls ClickHouse until the row
     * count reaches the snapshot minus tolerance.
     */
    private void reconcile(final String nl6Protocol, final String chProtocol, final double epsilon) throws Exception {
        await(Duration.ofMinutes(3), "nl6 ledger to reach " + MIN_RECORDS + " " + nl6Protocol + " records",
                () -> sentRecordsUnchecked(nl6Protocol) >= MIN_RECORDS);

        final long ledger = NL6.sentRecords(nl6Protocol);
        final long threshold = (long) Math.ceil(ledger * (1.0 - epsilon));

        await(Duration.ofMinutes(2), chProtocol + " rows in ClickHouse to reach " + threshold + " (ledger " + ledger + ")",
                () -> countRows(chProtocol) >= threshold);

        final var row = queryClient.queryAll(
                "SELECT exporterAddr, application FROM flows WHERE flowProtocol = '" + chProtocol + "' LIMIT 1").getFirst();
        Assertions.assertThat(row.getString("exporterAddr")).isNotEmpty();
    }

    private void verifyTimestampsSane(final String chProtocol) throws Exception {
        final var row = queryClient.queryAll(
                "SELECT min(firstSwitched) AS minFirst, max(lastSwitched) AS maxLast FROM flows WHERE flowProtocol = '"
                        + chProtocol + "'").getFirst();
        final var window = Duration.ofHours(1);
        Assertions.assertThat(row.getLocalDateTime("minFirst").atZone(java.time.ZoneId.systemDefault()).toInstant())
                .isAfter(TEST_START.minus(window));
        Assertions.assertThat(row.getLocalDateTime("maxLast").atZone(java.time.ZoneId.systemDefault()).toInstant())
                .isBefore(Instant.now().plus(window));
    }

    private long countRows(final String chProtocol) {
        try {
            return queryClient.queryAll("SELECT count() AS c FROM flows WHERE flowProtocol = '" + chProtocol + "'")
                    .getFirst().getLong("c");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long sentRecordsUnchecked(final String protocol) {
        try {
            return NL6.sentRecords(protocol);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

}
