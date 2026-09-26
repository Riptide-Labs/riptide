/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.config.DaemonConfig;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.e2e.ContainerImages;
import org.riptide.inventory.CollectionName;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.PollingProfile;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.metrics.MetricsConfig;
import org.riptide.metrics.PrometheusRemoteWriteSink;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The full chain against a real store: an inventory-registered {@code poll: always} device,
 * the real {@link InterfaceSnapshotPoller}, the real {@link PrometheusRemoteWriteSink} with its
 * hand-written snappy encoder, and a VictoriaMetrics container that must decode the payload and
 * answer a PromQL {@code rate()} query.
 *
 * <p>Covers one device and one collection ({@code if-mib-interfaces}) registered by inventory
 * {@code poll: always}. The flow-arrival registration path (a device polled from its first flow
 * rather than from the inventory) is the same code path and is covered by
 * {@code InterfaceSnapshotPollerTest} instead. This test proves neither multi-device fan-out nor
 * any collection other than {@code if-mib-interfaces}, and does not re-run the unit suite.
 */
@Testcontainers
class SnmpMetricsIT {

    // latencyOffset: VictoriaMetrics hides the newest 30 s from queries by default, which would
    // make a short test wait for nothing
    @Container
    private static final GenericContainer<?> VM = new GenericContainer<>(ContainerImages.victoriametrics())
            .withCommand("-search.latencyOffset=1s", "-retentionPeriod=1d")
            .withExposedPorts(8428)
            .waitingFor(Wait.forHttp("/health").forPort(8428).forStatusCode(200));

    private static final int SILENT_PORT = 12501;

    private TestSnmpAgent silent;
    private DefaultSnmpService snmp;
    private PrometheusRemoteWriteSink sink;
    private InterfaceSnapshotPoller poller;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start(@TempDir final Path dir) throws Exception {
        this.silent = new TestSnmpAgent("127.0.0.1/" + SILENT_PORT, dir);
        this.silent.start();
        this.silent.registerIfTable();
        this.silent.registerIfXTable();

        final var metrics = new MetricRegistry();
        this.snmp = new DefaultSnmpService(SecretResolvers.defaults(), metrics);

        final var remoteWrite = new MetricsConfig.RemoteWrite();
        remoteWrite.setUrl(vmUrl("/api/v1/write"));
        remoteWrite.getBatch().setMaxLatency(Duration.ofMillis(200));
        this.sink = new PrometheusRemoteWriteSink(remoteWrite, SecretResolvers.defaults(), new OutboundHttpTrust(), metrics);
        this.sink.start();

        // the loader's cleartext-width rule (AD-8) only rejects a v1/v2c set on a range wider
        // than one address; a /32 host range is single-address, so v2c is fine here
        final var credentials = CredentialSet.community(CredentialVersion.V2C, SecretRef.of(TestSnmpAgent.COMMUNITY));
        final var profiles = new SnmpProfilesConfig(Map.of("public", credentials),
                Map.of("counters", new PollingProfile(Duration.ofSeconds(2), Duration.ofMinutes(1), 500, 1,
                        List.of(CollectionName.IF_MIB_INTERFACES))));
        final var inventory = new Inventory(profiles, new FileInventoryDocument(new InventoryConfig()));
        inventory.swap(InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      127.0.0.1/32: { credentials: public, polling: counters, port: %d }
                  exporters:
                    silent-switch: { address: 127.0.0.1, poll: always }
                """.formatted(SILENT_PORT), "it.yaml"));

        final var pollConfig = new SnmpPollConfig();
        final var daemon = new DaemonConfig();
        this.poller = new InterfaceSnapshotPoller(this.snmp, pollConfig, metrics, inventory, this.sink, daemon,
                System::nanoTime, true, System::currentTimeMillis);
    }

    @AfterEach
    void stop() {
        this.poller.stop();
        this.sink.stop();
        this.snmp.close();
        this.silent.stop();
    }

    @Test
    void countersFromASilentDeviceAreQueryableAsARateInVictoriaMetrics() throws Exception {
        long octets = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            octets += 2_000; // 1000 bytes/s at a 2 s interval
            this.silent.setCounter(1, 6, octets);
            Thread.sleep(2_000);
        }
        // walks continue on their own schedule while the assertion retries, so each retry
        // flushes again rather than querying once against data frozen at the first flush
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            forceFlush();
            final JsonNode result = query("rate(ifHCInOctets{exporter=\"silent-switch\",ifIndex=\"1\"}[10s])");
            assertThat(result.path("data").path("result")).isNotEmpty();
            final double rate = result.path("data").path("result").get(0).path("value").get(1).asDouble();
            assertThat(rate).isBetween(900d, 1_100d);
        });
        forceFlush();
        final JsonNode info = query("riptide_interface_info{exporter=\"silent-switch\",ifIndex=\"1\"}");
        assertThat(info.path("data").path("result").get(0).path("metric").path("ifAlias").asText())
                .isEqualTo("My ethernet interface");
    }

    private void forceFlush() throws Exception {
        this.http.send(HttpRequest.newBuilder(URI.create(vmUrl("/internal/force_flush"))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode query(final String promql) throws Exception {
        final String url = vmUrl("/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        final HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return new ObjectMapper().readTree(response.body());
    }

    private static String vmUrl(final String path) {
        return "http://" + VM.getHost() + ":" + VM.getMappedPort(8428) + path;
    }
}
