/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.riptide.inventory.ExporterEntry;
import org.riptide.inventory.Inventory;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.utils.HttpServerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Boot with the discovery endpoint down, end to end: the spec's "boot does not fail when the
 * endpoint is unreachable. It warns, serves the file-only inventory, and lets the schedule heal."
 *
 * <p>Its own context, because the property under test is how the context boots. The endpoint
 * answers 404 until the test lets it recover. A 404 rather than a refused connection on purpose:
 * the watcher treats a 404 as absence and skips without recomputing staleness, so a gauge reading 1
 * here can only be the latch set at start, never one a failed cycle happened to set first.</p>
 */
@ExtendWith(DiscoveryBootDegradedTest.StopTheEndpointAfterTheContext.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiscoveryBootDegradedTest {

    private static final Duration PICKUP_CEILING = Duration.ofSeconds(30);

    /** What the endpoint answers with; 404 until the test lets it recover. */
    private static final AtomicInteger STATUS = new AtomicInteger(404);

    private static final HttpServer SERVER = startServer();

    private static final Path INVENTORY = writeInventory();

    @Autowired
    private Inventory inventory;

    @Autowired
    private MetricRegistry metrics;

    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/devices", exchange -> {
                try (exchange) {
                    final int status = STATUS.get();
                    if (status != 200) {
                        exchange.sendResponseHeaders(status, -1);
                        return;
                    }
                    final byte[] answer = """
                            [{"targets":["firewall-01"],
                              "labels":{"__meta_netbox_name":"firewall-01",
                                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.start();
            return server;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not start the discovery server", e);
        }
    }

    /** An agent range needing no credential set, so the test depends on no SNMP configuration. */
    private static Path writeInventory() {
        try {
            final Path file = Files.createTempFile("discovery-boot-degraded", ".yaml");
            file.toFile().deleteOnExit();
            Files.writeString(file, """
                    riptide:
                      snmp:
                        agents:
                          "10.20.0.0/16":
                            enabled: false
                    """);
            return file;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write the inventory file", e);
        }
    }

    /** Stops the endpoint after Spring closes the context; see {@code DiscoveryReloadTest}'s twin. */
    static final class StopTheEndpointAfterTheContext implements AfterAllCallback {
        @Override
        public void afterAll(final ExtensionContext context) {
            SERVER.stop(0);
        }
    }

    @DynamicPropertySource
    static void discoveryEndpoint(final DynamicPropertyRegistry registry) {
        registry.add("riptide.discovery.url",
                () -> "http://" + SERVER.getAddress().getHostString()
                        + ":" + SERVER.getAddress().getPort() + "/devices");
        registry.add("riptide.discovery.interval", () -> "200ms");
        registry.add("riptide.inventory.file", INVENTORY::toString);
    }

    @Test
    @Timeout(60)
    void bootServesTheFileWithTheEndpointDownAndTheScheduleHealsIt() throws Exception {
        assertThat(this.inventory.snapshot().agentCount())
                .as("the context started and serves the file's agent ranges")
                .isEqualTo(1);
        assertThat(this.inventory.snapshot().exporterCount())
                .as("with no exporters while the endpoint is down")
                .isZero();
        assertThat(stale())
                .as("and the staleness gauge says so")
                .isEqualTo(1);

        STATUS.set(200);
        final ExporterIdentity firewall = new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.0.0.1"), 0L);

        await("the schedule to publish the discovered exporter once the endpoint answers", () ->
                this.inventory.snapshot().exporterView().match(firewall).isPresent());
        // a separate wait: the reload publishes, then clears the gauge
        await("the staleness gauge to clear once the discovered exporters are published",
                () -> stale() == 0);

        assertThat(this.inventory.snapshot().exporterView().match(firewall))
                .get()
                .extracting(ExporterEntry::name)
                .isEqualTo("firewall-01");
        assertThat(this.inventory.snapshot().agentCount())
                .as("the agent ranges survive the heal")
                .isEqualTo(1);
    }

    private int stale() {
        final Gauge<?> gauge = this.metrics.getGauges().get("inventory.reload.stale");
        assertThat(gauge).as("inventory.reload.stale is registered").isNotNull();
        return (Integer) gauge.getValue();
    }

    /** The same bounded poll as {@code DiscoveryReloadTest.await}. */
    private static void await(final String what, final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + PICKUP_CEILING.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        fail("timed out after %s waiting for %s".formatted(PICKUP_CEILING, what));
    }
}
