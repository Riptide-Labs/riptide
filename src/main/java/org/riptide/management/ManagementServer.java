/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal HTTP server (JDK {@link HttpServer}, no web application server) exposing {@code /livez}
 * and {@code /readyz} on the management port for Kubernetes probes and Docker Compose health checks.
 */
@Slf4j
@Component
public class ManagementServer {

    private final RiptideManagementProperties properties;
    private final HealthService health;

    private HttpServer server;
    private ExecutorService executor;

    public ManagementServer(final RiptideManagementProperties properties, final HealthService health) {
        this.properties = properties;
        this.health = health;
    }

    @PostConstruct
    void start() throws IOException {
        if (!this.properties.isEnabled()) {
            log.info("Management server disabled (riptide.management.enabled=false)");
            return;
        }

        this.server = HttpServer.create(
                new InetSocketAddress(this.properties.getBindAddress(), this.properties.getPort()), 0);
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactoryBuilder()
                .setNameFormat("management-http-%d")
                .setDaemon(true)
                .build());
        this.server.setExecutor(this.executor);
        this.server.createContext("/livez", exchange -> respond(exchange, this.health.liveness()));
        this.server.createContext("/readyz", exchange -> respond(exchange, this.health.readiness()));
        this.server.start();

        log.info("Management server listening on {}:{} (/livez, /readyz)",
                this.properties.getBindAddress(), this.properties.getPort());
    }

    @PreDestroy
    void stop() {
        if (this.server != null) {
            this.server.stop(0);
        }
        if (this.executor != null) {
            // stop() does not shut down a user-set executor
            this.executor.shutdownNow();
        }
    }

    private static void respond(final HttpExchange exchange, final Health health) throws IOException {
        final byte[] body = ((health.up() ? "ok" : "unavailable") + ": " + health.detail() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(health.up() ? 200 : 503, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
