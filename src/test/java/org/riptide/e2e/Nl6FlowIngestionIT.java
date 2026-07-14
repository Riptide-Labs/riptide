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
import java.util.Map;

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

        // Two nodes on one subnet to prove sFlow sub-agent pinning: the pinned node
        // (observation-domain = the sub_agent_id) beats the wildcard. Distinct static
        // interface names let the query tell which node matched.
        registry.add("riptide.nodes.sflow-pinned.subnet-address", () -> "10.42.6.0/24");
        registry.add("riptide.nodes.sflow-pinned.observation-domain", () -> "7");
        registry.add("riptide.nodes.sflow-pinned.interfaces.1.name", () -> "pinned-in");
        registry.add("riptide.nodes.sflow-wild.subnet-address", () -> "10.42.6.0/24");
        registry.add("riptide.nodes.sflow-wild.interfaces.1.name", () -> "wild-in");
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

        // Interface option records (nl6 v0.16.0) — SNMP-less enrichment, both wire shapes:
        // if-scoped emits IE 82+83 (name via scope), system-scoped emits IE 83 only
        // (description via the INPUT_SNMP field fallback — the real IOS-XR shape).
        // These add flow data to the v9/ipfix reconcile counts (fine — both sides grow);
        // nl6 excludes option records from sent_records, so the ledger stays clean.
        NL6.createDevices("10.42.4.1", 2, "netflow9", NETFLOW9_PORT, Map.of("options_interface_table", "if-scoped"));
        NL6.createDevices("10.42.5.1", 2, "ipfix", IPFIX_PORT, Map.of("options_interface_table", "system-scoped"));

        // sFlow sub-agent pinning — a device group per sub_agent_id on one subnet.
        NL6.createDevices("10.42.6.1", 2, "sflow", SFLOW_PORT, Map.of("sub_agent_id", 7));    // → sflow-pinned
        NL6.createDevices("10.42.6.21", 2, "sflow", SFLOW_PORT, Map.of("sub_agent_id", 3));   // → sflow-wild
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

        final var algorithms = queryClient.queryAll(
                "SELECT DISTINCT samplingAlgorithm FROM flows WHERE flowProtocol = 'SFLOW'");
        Assertions.assertThat(algorithms).hasSize(1);
        Assertions.assertThat(algorithms.getFirst().getString("samplingAlgorithm"))
                .isEqualTo("RandomNOutOfNSampling");

        // every sFlow exporter is a device address (10.42.x), never the shared-socket
        // container IP — the payload-derived-attribution proof
        final var exporters = queryClient.queryAll(
                "SELECT DISTINCT exporterAddr FROM flows WHERE flowProtocol = 'SFLOW'");
        Assertions.assertThat(exporters).isNotEmpty().allSatisfy(row ->
                Assertions.assertThat(row.getString("exporterAddr")).startsWith("10.42."));
        Assertions.assertThat(exporters).anySatisfy(row ->
                Assertions.assertThat(row.getString("exporterAddr")).startsWith("10.42.3."));

        await(Duration.ofMinutes(1), "sFlow rows enriched via the agent-address-matched node",
                () -> queryClient.queryAll(
                        "SELECT count() AS c FROM flows WHERE flowProtocol = 'SFLOW' AND inputSnmpIfName = 'sfl-in'")
                        .getFirst().getLong("c") > 0);
    }

    /**
     * sFlow sub-agent pinning (nl6 v0.16.0 configurable {@code sub_agent_id}): two nodes
     * share subnet 10.42.6.0/24, one pinned to observation-domain 7. Devices sending
     * {@code sub_agent_id} 7 must attribute to the pinned node, others to the wildcard —
     * proving {@code NodeRegistry} keys sFlow by {@code (agent_address, sub_agent_id)}.
     */
    @Test
    void verifySflowSubAgentPinning() throws Exception {
        await(Duration.ofMinutes(2), "sub_agent_id 7 devices attribute to the pinned node",
                () -> queryClient.queryAll(
                        "SELECT count() AS c FROM flows WHERE flowProtocol = 'SFLOW' "
                        + "AND exporterAddr IN ('10.42.6.1','10.42.6.2') AND inputSnmpIfName = 'pinned-in'")
                        .getFirst().getLong("c") > 0);

        await(Duration.ofMinutes(2), "other sub_agent_id devices attribute to the wildcard node",
                () -> queryClient.queryAll(
                        "SELECT count() AS c FROM flows WHERE flowProtocol = 'SFLOW' "
                        + "AND exporterAddr IN ('10.42.6.21','10.42.6.22') AND inputSnmpIfName = 'wild-in'")
                        .getFirst().getLong("c") > 0);
    }

    /**
     * Interface enrichment from v9/IPFIX option records (nl6 v0.16.0), with no SNMP and
     * no node configured — pure option-table enrichment. The if-scoped shape carries
     * IE 82 (name); the system-scoped shape carries IE 83 only (description → alias),
     * exercising riptide's scope-resolution and field-fallback paths respectively.
     */
    @Test
    void verifyOptionRecordEnrichmentWithoutSnmp() throws Exception {
        // if-scoped (NetFlow v9): IE 82 present → interface NAME enriched
        await(Duration.ofMinutes(2), "netflow9 flows enriched with an interface name from option records",
                () -> queryClient.queryAll(
                        "SELECT count() AS c FROM flows WHERE flowProtocol = 'NetflowV9' "
                        + "AND inputSnmpIfName IS NOT NULL AND inputSnmpIfName != ''")
                        .getFirst().getLong("c") > 0);

        // system-scoped (IPFIX): IE 83 only → interface ALIAS enriched, name absent
        await(Duration.ofMinutes(2), "ipfix flows enriched with an interface alias (description-only) from option records",
                () -> queryClient.queryAll(
                        "SELECT count() AS c FROM flows WHERE flowProtocol = 'IPFIX' "
                        + "AND inputSnmpIfAlias IS NOT NULL AND inputSnmpIfAlias != ''")
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
