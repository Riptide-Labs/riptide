/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

import static org.riptide.e2e.E2eTestSupport.awaitCount;
import static org.riptide.e2e.E2eTestSupport.freeUdpPort;

/**
 * Full-mode e2e: nl6 devices export flows with per-device source IPs and
 * riptide's SNMP enrichment walks back to each device's nl6 SNMP agent.
 *
 * Environment contract (see README "full mode" and the e2e CI job):
 * - Docker network "nl6-fullmode" exists with subnet 172.30.42.0/24
 * - host route: ip route add 10.42.0.0/16 via 172.30.42.10 (+ loose rp_filter)
 * - RIPTIDE_E2E_FULL_MODE=1 set (the gate)
 *
 * Without the gate this class is SKIPPED. With the gate set, a broken
 * environment (missing route, unreachable agents) FAILS loudly.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@EnabledIfEnvironmentVariable(named = "RIPTIDE_E2E_FULL_MODE", matches = "1",
        disabledReason = "full-mode e2e needs a Linux host with a route into nl6's device network (see README)")
public class Nl6SnmpEnrichmentIT {

    private static final String FULL_MODE_NETWORK = "nl6-fullmode";
    private static final String NL6_STATIC_IP = "172.30.42.10";
    private static final int DEVICE_COUNT = 5;
    private static final String FIRST_DEVICE = "10.42.0.1";
    private static final long MIN_RECORDS = 50;

    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final Nl6Container NL6 = new Nl6Container().perDevice(FULL_MODE_NETWORK, NL6_STATIC_IP);

    private static final int NETFLOW9_PORT = freeUdpPort();

    private static Client queryClient;
    private static Map<Integer, String> groundTruthIfNames;

    static {
        // Containers must outlive this class's CACHED Spring context (closed only
        // at JVM exit): stopping them per-class (@Container) leaves the context's
        // pipeline retrying inserts into a dead ClickHouse and starves later e2e
        // classes in the same JVM. Started here, reaped by Ryuk at JVM death —
        // same lifecycle as Nl6FlowIngestionIT. This block never runs when the
        // class is disabled by the RIPTIDE_E2E_FULL_MODE gate (no class init).
        CLICKHOUSE.start();
        NL6.start();
    }

    /** Resolved once: a dynamic-property supplier runs on every lookup. */
    private static final String INVENTORY_FILE = inventoryFile().toString();

    @DynamicPropertySource
    static void riptideProperties(final DynamicPropertyRegistry registry) {
        registry.add("riptide.clickhouse.endpoint",
                () -> "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        registry.add("riptide.clickhouse.username", () -> "riptide");
        registry.add("riptide.clickhouse.password", () -> "riptide");

        registry.add("riptide.receivers.nf9.type", () -> "netflow9");
        registry.add("riptide.receivers.nf9.host", () -> "0.0.0.0");
        registry.add("riptide.receivers.nf9.port", () -> NETFLOW9_PORT);

        // Credentials live in the main config; the ranges that reference them live in the
        // inventory file. The devices are enumerated as single addresses rather than
        // declared as one 10.42.0.0/16 range because a range wider than one host may not
        // carry a v1/v2c credential set: the community would go to anything in the range
        // that emits a flow. The nl6 agent speaks v2c, so enumeration is the remedy the
        // rule itself names. Demonstrating zero-touch onboarding across a whole range
        // needs a v3-capable agent, which is a question for the nl6 image.
        registry.add("riptide.snmp.credentials.nl6.version", () -> "v2c");
        registry.add("riptide.snmp.credentials.nl6.community", () -> "public");
        registry.add("riptide.inventory.file", () -> INVENTORY_FILE);
    }

    private static java.nio.file.Path inventoryFile() {
        try {
            final var file = java.nio.file.Files.createTempFile("riptide-e2e-snmp-inventory", ".yaml");
            file.toFile().deleteOnExit();
            final var agents = new StringBuilder("riptide:\n  snmp:\n    agents:\n");
            for (int device = 1; device <= DEVICE_COUNT; device++) {
                agents.append("      \"10.42.0.").append(device).append("\":\n")
                        .append("        credentials: nl6\n")
                        .append("        port: 161\n");
            }
            java.nio.file.Files.writeString(file, agents.toString());
            return file;
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("could not write the e2e inventory file", e);
        }
    }

    @BeforeAll
    static void startTrafficAndVerifyEnvironment() throws Exception {
        queryClient = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername("riptide")
                .setPassword("riptide")
                .setDefaultDatabase("riptide")
                .build();

        NL6.createDevices(FIRST_DEVICE, DEVICE_COUNT, "netflow9", NETFLOW9_PORT);

        // Gate is set, so a broken environment must FAIL, not limp along:
        // the ground-truth walk exercises the host->netns route and the agent.
        groundTruthIfNames = SnmpIfNameWalker.walkIfNames(FIRST_DEVICE);
        if (groundTruthIfNames.isEmpty()) {
            Assertions.fail(("RIPTIDE_E2E_FULL_MODE is set but no SNMP response from %s - is the host route "
                    + "'ip route add 10.42.0.0/16 via %s' in place and rp_filter loose?")
                    .formatted(FIRST_DEVICE, NL6_STATIC_IP));
        }
    }

    @Test
    void verifyPerDeviceExporterAttribution() throws Exception {
        awaitCount(Duration.ofMinutes(3), "nl6 ledger to reach " + MIN_RECORDS + " netflow9 records",
                () -> sentRecordsUnchecked(), MIN_RECORDS);

        // Waits for >= DEVICE_COUNT; the containsExactly below still pins the count to exactly DEVICE_COUNT.
        awaitCount(Duration.ofMinutes(2), DEVICE_COUNT + " distinct exporter addresses in ClickHouse",
                () -> countDistinctExporters(), DEVICE_COUNT);

        final var exporters = queryClient.queryAll("SELECT DISTINCT exporterAddr FROM flows ORDER BY exporterAddr")
                .stream().map(r -> r.getString("exporterAddr")).toList();
        Assertions.assertThat(exporters)
                .containsExactly("10.42.0.1", "10.42.0.2", "10.42.0.3", "10.42.0.4", "10.42.0.5");
    }

    @Test
    void verifyIfNameEnrichmentAgainstAgent() throws Exception {
        awaitCount(Duration.ofMinutes(3), "enriched interface names for exporter " + FIRST_DEVICE,
                () -> countEnrichedRows(FIRST_DEVICE), 1);

        final var row = queryClient.queryAll(
                "SELECT any(inputSnmpIfName) AS inName, any(outputSnmpIfName) AS outName FROM flows"
                        + " WHERE exporterAddr = '" + FIRST_DEVICE + "' AND inputSnmpIfName IS NOT NULL").getFirst();

        // nl6 flows always carry ifIndex 1 (ingress) and 2 (egress); the agent's
        // ifXTable is the ground truth for what those must resolve to.
        Assertions.assertThat(row.getString("inName")).isEqualTo(groundTruthIfNames.get(1));
        Assertions.assertThat(row.getString("outName")).isEqualTo(groundTruthIfNames.get(2));
    }

    private long countDistinctExporters() {
        try {
            return queryClient.queryAll("SELECT uniqExact(exporterAddr) AS c FROM flows").getFirst().getLong("c");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long countEnrichedRows(final String exporter) {
        try {
            return queryClient.queryAll("SELECT count() AS c FROM flows WHERE exporterAddr = '" + exporter
                    + "' AND inputSnmpIfName IS NOT NULL AND outputSnmpIfName IS NOT NULL").getFirst().getLong("c");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long sentRecordsUnchecked() {
        try {
            return NL6.sentRecords("netflow9");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
