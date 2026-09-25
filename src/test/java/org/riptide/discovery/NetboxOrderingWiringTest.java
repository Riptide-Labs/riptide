/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.utils.HttpServerConfig;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query string {@code netbox-api} actually sends, read off the wire through the real bean
 * wiring. {@code NetboxDeviceSourceTest} pins the URL {@code firstPage} builds, but nothing
 * pinned that {@code DiscoveryConfiguration} asks for the ordered form: flipping its
 * {@code ordered} argument to {@code false} left the whole suite green (#888). Without
 * {@code ordering=id} NetBox pages by offset over an unordered result, so a device added
 * mid-walk can be returned twice or missed, and no fleet small enough for one page shows it.
 */
class NetboxOrderingWiringTest {

    private static final String DEVICES = "/api/dcim/devices/";
    private static final String VMS = "/api/virtualization/virtual-machines/";

    /** Every request's path and raw query, per path, in arrival order. */
    private final Map<String, List<String>> queries = new ConcurrentHashMap<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        HttpServerConfig.ensureApplied();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        answer(DEVICES, "sw1", "10.0.0.1/24");
        answer(VMS, "vm1", "10.0.0.2/24");
        this.server.start();
    }

    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    /** One NetBox page holding one object, whatever the query. */
    private void answer(final String path, final String name, final String address) {
        this.server.createContext(path, exchange -> {
            try (exchange) {
                this.queries.computeIfAbsent(path, key -> new CopyOnWriteArrayList<>())
                        .add(String.valueOf(exchange.getRequestURI().getRawQuery()));
                final byte[] body = """
                        {"count": 1, "next": null, "previous": null,
                         "results": [{"id": 1, "name": "%s", "primary_ip4": {"address": "%s"}}]}
                        """.formatted(name, address).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
    }

    @Test
    void everyListedEndpointIsAskedForTheStableOrdering() {
        final String base = "http://127.0.0.1:" + this.server.getAddress().getPort();
        new ApplicationContextRunner()
                .withUserConfiguration(DiscoveryWiringTest.Collaborators.class, DiscoveryConfiguration.class)
                .withPropertyValues("riptide.discovery.type=netbox-api",
                        "riptide.discovery.urls[0]=" + base + DEVICES,
                        "riptide.discovery.urls[1]=" + base + VMS,
                        "riptide.discovery.filter=tag=flow-exporter")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // the composed document's strict read: the path every poll takes
                    assertThat(context.getBean(ComposedInventoryDocument.class).text())
                            .contains("sw1").contains("vm1");
                });

        assertThat(this.queries).containsOnlyKeys(DEVICES, VMS);
        this.queries.forEach((path, sent) -> assertThat(sent)
                .as("the first request to %s", path)
                .first()
                .isEqualTo("tag=flow-exporter&ordering=id"));
    }
}
