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

import java.net.DatagramSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * End-to-end: nl6-simulated devices export NetFlow v5 / v9 / IPFIX over real
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

    private static void await(final Duration timeout, final String description, final BooleanSupplier condition)
            throws InterruptedException {
        final var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(2000);
        }
        Assertions.fail("Timed out after %s waiting for %s".formatted(timeout, description));
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (final Exception e) {
            throw new IllegalStateException("No free UDP port available", e);
        }
    }
}
