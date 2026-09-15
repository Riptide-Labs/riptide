/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventorySnapshot;
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
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Discovery end to end: the real context, a loopback endpoint, and nothing driven by hand. No test
 * here calls {@code poll()}; every change reaches the inventory through the production schedule at
 * {@code riptide.discovery.interval}, which is the whole point.
 *
 * <p>{@code riptide.inventory.file} is left unset, so discovery supplies the whole document. That is
 * also the configuration in which a watcher still reading {@code this.location} fails first.</p>
 *
 * <p><b>Ordered on purpose.</b> The tests share one context and one static endpoint body, and the
 * boot assertion is only meaningful before a later test renames the exporter. JUnit's default method
 * order is unspecified, so the order is declared rather than stated in prose.</p>
 *
 * <p><b>The endpoint stops after the context, not before.</b> See {@link StopTheEndpointAfterTheContext};
 * the {@code @ExtendWith} for it must stay above {@code @SpringBootTest}.</p>
 */
@ExtendWith(DiscoveryReloadTest.StopTheEndpointAfterTheContext.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiscoveryReloadTest {

    private static final Duration PICKUP_CEILING = Duration.ofSeconds(30);

    /** What the endpoint answers next; the schedule thread reads what a test writes. */
    private static final AtomicReference<String> BODY = new AtomicReference<>("""
            [{"targets":["firewall-01"],
              "labels":{"__meta_netbox_name":"firewall-01",
                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
            """);

    /** Requests actually served, so "polled and refused" is distinguishable from "never polled". */
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    private static final HttpServer SERVER = startServer();

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
                    // counted before the body is read, so a request counted after a test's
                    // BODY.set() is guaranteed to be answered with what that test set
                    REQUESTS.incrementAndGet();
                    final byte[] answer = BODY.get().getBytes(StandardCharsets.UTF_8);
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

    /**
     * Stops the endpoint once Spring has closed the context, so the 200ms watcher is gone before
     * its server is.
     *
     * <p>A plain {@code @AfterAll} cannot do this: JUnit runs every {@code @AfterAll} method before
     * any extension's {@code afterAll}, and {@code @DirtiesContext(AFTER_CLASS)} closes the context
     * in {@code SpringExtension}'s. The server was stopped while the watcher still polled it, and
     * each poll in that window logged a connection-refused WARN with a stack trace. Extensions'
     * {@code afterAll} callbacks run in reverse registration order, and class-level
     * {@code @ExtendWith} registers in declaration order, so declaring this one above
     * {@code @SpringBootTest} (whose meta-annotation registers {@code SpringExtension}) makes it
     * run after the context is closed.</p>
     */
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
    }

    private static ExporterIdentity identity(final String address) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), 0L);
    }

    @Test
    @Order(1)
    @Timeout(60)
    void bootPublishesTheDiscoveredExporters() throws Exception {
        assertThat(this.inventory.snapshot().exporterView().match(identity("10.0.0.1")))
                .get()
                .extracting("name")
                .isEqualTo("firewall-01");
    }

    /**
     * A credential rotation in {@code ConfigFileReloader} rebuilds through
     * {@code Inventory.rebuildAndSwap}, at two sites. Both read the document {@code Inventory} was
     * given, so this pins that the rotation path reads the composed document: a rebuild from the
     * file alone would carry no exporters.
     */
    @Test
    @Order(2)
    @Timeout(60)
    void aCredentialRotationRebuildsFromTheComposedDocument() throws Exception {
        final InventorySnapshot rebuilt = this.inventory.rebuildAndSwap(this.inventory.profiles());

        assertThat(rebuilt).as("a rebuild that would drop a tree is refused as null").isNotNull();
        assertThat(rebuilt.exporterView().match(identity("10.0.0.1")))
                .get()
                .extracting("name")
                .isEqualTo("firewall-01");
    }

    @Test
    @Order(3)
    @Timeout(60)
    void aChangedDocumentSwapsTheInventory() throws Exception {
        BODY.set("""
                [{"targets":["firewall-01"],
                  "labels":{"__meta_netbox_name":"renamed-01",
                            "__meta_netbox_primary_ip4":"10.0.0.1"}}]
                """);
        final ExporterIdentity firewall = identity("10.0.0.1");

        await("the schedule to fetch, compose and publish the renamed exporter", () ->
                this.inventory.snapshot().exporterView().match(firewall)
                        .filter(entry -> "renamed-01".equals(entry.name()))
                        .isPresent());
    }

    @Test
    @Order(4)
    @Timeout(60)
    void anEmptyAnswerKeepsTheLastGoodInventoryServing() throws Exception {
        final long failedBefore = failures().getCount();
        BODY.set("[]");
        final int requestsBefore = REQUESTS.get();

        // observed polls, not a sleep: a duration can elapse with no poll at all, and then an
        // unchanged inventory proves nothing. Neutral on purpose, so the assertions below are what
        // fail when the property breaks. Three requests: the schedule is fixed-delay, so the third
        // cannot start before the first two cycles have finished with the empty answer
        await("three polls answered with the empty document",
                () -> REQUESTS.get() >= requestsBefore + 3);

        assertThat(this.inventory.snapshot().exporterCount())
                .as("an empty answer must never empty the exporters tree")
                .isPositive();
        assertThat(failures().getCount())
                .as("and it is refused as a counted failure, not skipped as if nothing was there")
                .isGreaterThanOrEqualTo(failedBefore + 2);
    }

    /**
     * Read through {@code getCounters()} rather than {@code counter(name)}: the latter creates a
     * missing counter and hands back a zero, so a renamed metric would wait out the ceiling instead
     * of failing on the name.
     */
    private Counter failures() {
        final Counter counter = this.metrics.getCounters().get("inventory.reload.failures");
        assertThat(counter).as("inventory.reload.failures is registered").isNotNull();
        return counter;
    }

    /**
     * A bounded poll, modelled on {@code ClassificationRulesTestSupport.await}, which is
     * package-private to the classification package and so not reachable from here. Awaitility is
     * not on the test classpath, and one loop does not justify adding it.
     */
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
