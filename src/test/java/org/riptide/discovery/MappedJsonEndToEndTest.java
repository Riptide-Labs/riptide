/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.riptide.utils.HttpServerConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mapped source against a real HTTP server, through the real client.
 *
 * <p>The unit tests stub the page reader, which proves the mapping and skips the fetch. This one
 * runs the whole path an operator gets: the bounded read, the token header, the walk, the paths and
 * the shared renderer (#800).</p>
 *
 * <p><b>Two shapes, chosen deliberately.</b> The first is one neither existing source can read: a
 * doubly-nested envelope, a device name two levels down, and paging under a key that is not
 * {@code next}. The second is NetBox's own shape, to show the design's central trade-off meeting a
 * real one: the name maps and the address does not, because it carries a prefix length.</p>
 *
 * <p><b>A limit worth stating.</b> The NetBox case uses that API's response shape, recorded from the
 * saved lab, rather than a running NetBox. What it proves is how this source behaves against that
 * shape; it is not a live-integration claim.</p>
 */
class MappedJsonEndToEndTest {

    private static final AtomicReference<String> PAGE_ONE = new AtomicReference<>();
    private static final AtomicReference<String> PAGE_TWO = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/assets", exchange -> {
                try (exchange) {
                    final boolean second = "page=2".equals(exchange.getRequestURI().getQuery());
                    final byte[] answer = (second ? PAGE_TWO.get() : PAGE_ONE.get())
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.start();
            return server;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not start the test server", e);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    private static String endpoint() {
        return "http://" + SERVER.getAddress().getHostString() + ":" + SERVER.getAddress().getPort() + "/assets";
    }

    private static MappedJsonSource source(final String items, final String name,
                                           final String address, final String next) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl(endpoint());
        config.setToken(SecretRef.of("s3cret"));
        final DiscoveryClient client = new DiscoveryClient(config, SecretResolvers.defaults());
        return new MappedJsonSource(
                NetboxDeviceSource.firstPage(config.endpoint(), null),
                client::fetchPage, client::describe,
                new MappedJsonSource.MappingPaths(
                        JsonPath.of(items, "riptide.discovery.mapping.items"),
                        JsonPath.of(name, "riptide.discovery.mapping.name"),
                        JsonPath.of(address, "riptide.discovery.mapping.address"),
                        next == null ? null : JsonPath.of(next, "riptide.discovery.mapping.next")));
    }

    @Test
    void aShapeNoOtherSourceCouldReadRendersEndToEnd() throws Exception {
        PAGE_ONE.set("""
                {"payload": {"inventory": {"nodes": [
                   {"identity": {"fqdn": "edge-01.dc1"}, "net": {"mgmt": {"v4": "10.0.0.1"}}}
                 ]}},
                 "cursor": {"more": "%s?page=2"}}
                """.formatted(endpoint()));
        PAGE_TWO.set("""
                {"payload": {"inventory": {"nodes": [
                   {"identity": {"fqdn": "edge-02.dc1"}, "net": {"mgmt": {"v4": "10.0.0.2"}}}
                 ]}},
                 "cursor": {}}
                """);

        final var groups = source("payload.inventory.nodes", "identity.fqdn",
                "net.mgmt.v4", "cursor.more").targets();
        final var rendered = ExporterRenderer.render(groups, List.of("__meta_netbox_primary_ip4"), "the endpoint");

        assertThat(rendered.byName())
                .as("two pages, a doubly-nested envelope, and paging under a key that is not 'next'")
                .containsExactly(java.util.Map.entry("edge-01.dc1", "10.0.0.1"),
                        java.util.Map.entry("edge-02.dc1", "10.0.0.2"));
    }

    /**
     * NetBox's own shape, which this source maps as far as it can and then says why it cannot
     * finish. The design's trade-off, meeting the endpoint that motivated the native source.
     */
    @Test
    void netboxsOwnShapeMapsTheNameAndSaysWhyTheAddressCannotBeUsed() {
        PAGE_ONE.set("""
                {"count": 1, "next": null, "previous": null, "results": [
                   {"id": 1, "name": "edge-01",
                    "status": {"value": "active", "label": "Active"},
                    "primary_ip4": {"id": 7, "address": "10.0.0.1/24"}}
                 ]}
                """);

        assertThatThrownBy(() -> source("results", "name", "primary_ip4.address", "next").targets())
                .as("the name maps; the address cannot, and the operator is told which and what to do")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.0.0.1/24")
                .hasMessageContaining("riptide.discovery.mapping.address")
                .hasMessageContaining("netbox-api");
    }
}
