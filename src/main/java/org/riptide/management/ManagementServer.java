/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.codahale.metrics.MetricRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.utils.HttpServerConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A minimal HTTP server (JDK {@link HttpServer}, no web application server) exposing {@code /livez}
 * and {@code /readyz} on the management port for Kubernetes probes and Docker Compose health checks,
 * and {@code /metrics} in Prometheus text format.
 */
@Slf4j
@Component
public class ManagementServer {

    /** Grace for in-flight exchanges at shutdown, and the ceiling on waiting for them. */
    private static final int SHUTDOWN_SECONDS = 5;

    private final RiptideManagementProperties properties;
    private final HealthService health;
    private final MetricRegistry metrics;

    private HttpServer server;
    private ExecutorService executor;
    private Semaphore inFlight;

    public ManagementServer(final RiptideManagementProperties properties, final HealthService health,
                            final MetricRegistry metrics) {
        this.properties = properties;
        this.health = health;
        this.metrics = metrics;
    }

    @PostConstruct
    void start() throws IOException {
        if (!this.properties.isEnabled()) {
            log.info("Management server disabled (riptide.management.enabled=false)");
            return;
        }

        // before create(), not after: the JDK reads its server config in a static
        // initializer that runs on the first HttpServer in the process (#545)
        HttpServerConfig.ensureApplied();
        this.server = HttpServer.create(
                new InetSocketAddress(this.properties.getBindAddress(), this.properties.getPort()), 0);
        // Virtual threads are always daemon, so the factory carries no setDaemon: keep that in mind
        // before swapping back to platform threads, or the JVM will refuse to exit holding the port.
        // The JVM is held up by Spring's own non-daemon keep-alive thread (spring.main.keep-alive).
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("management-http-", 0).factory());
        this.inFlight = new Semaphore(this.properties.getMaxConcurrentRequests());
        this.server.setExecutor(this.executor);
        this.server.createContext("/livez", exchange -> guarded(exchange, this.health::liveness));
        this.server.createContext("/readyz", exchange -> guarded(exchange, this.health::readiness));
        if (this.properties.isMetricsEnabled()) {
            this.server.createContext("/metrics", this::metrics);
        }
        this.server.start();

        log.info("Management server listening on {}:{} (/livez, /readyz{})",
                this.properties.getBindAddress(), getPort(),
                this.properties.isMetricsEnabled() ? ", /metrics" : "");
    }

    /**
     * The port actually bound, which differs from the configured one when that is 0. Mirrors
     * {@code McpSseServer.getPort()}. Reporting it matters beyond tests: a 0 in the config
     * would otherwise be echoed back in the startup log instead of the real port.
     */
    public int getPort() {
        return this.server != null ? this.server.getAddress().getPort() : this.properties.getPort();
    }

    /**
     * Bounded rather than immediate (#545). {@code stop(0)} returns at once and
     * {@code shutdownNow()} interrupts whatever is mid-response, so a probe in flight became
     * a connection reset during an orderly shutdown — a load balancer reads that as a
     * transport error rather than an answer. The delay lets in-flight exchanges finish; the
     * budget stops a stuck one from holding shutdown open, and says so instead of exiting
     * quietly.
     */
    @PreDestroy
    void stop() {
        if (this.server != null) {
            this.server.stop(SHUTDOWN_SECONDS);
        }
        if (this.executor != null) {
            // stop() does not shut down a user-set executor
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Management server still had requests in flight after {}s; interrupting them",
                            SHUTDOWN_SECONDS);
                    this.executor.shutdownNow();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                this.executor.shutdownNow();
            }
        }
    }

    /**
     * Admission control in front of a handler. A thread-per-task executor imposes no ceiling of its
     * own, so the cap is what stops a caller — this port is on all interfaces by default — from
     * making the collector hold an unbounded number of threads, exchanges and sockets. Shedding
     * with 503 rather than queueing keeps a probe's answer fast and honest under load.
     */
    private void guarded(final HttpExchange exchange, final Supplier<Health> health) throws IOException {
        if (!this.inFlight.tryAcquire()) {
            respond(exchange, Health.down("management server busy"));
            return;
        }
        try {
            respond(exchange, health.get());
        } finally {
            this.inFlight.release();
        }
    }

    /**
     * Prometheus scrape endpoint. Shares the probes' concurrency cap rather than carrying its own:
     * rendering walks the whole registry, so it is the more expensive of the two handlers and has
     * more reason to be bounded, not less. A scrape that loses the race is shed with 503, which
     * Prometheus records as a failed scrape rather than retrying into the contention.
     */
    private void metrics(final HttpExchange exchange) throws IOException {
        if (!this.inFlight.tryAcquire()) {
            respond(exchange, 503, "text/plain; charset=utf-8", "management server busy\n");
            return;
        }
        try {
            respond(exchange, 200, "text/plain; version=0.0.4; charset=utf-8",
                    PrometheusExposition.render(this.metrics));
        } finally {
            this.inFlight.release();
        }
    }

    private static void respond(final HttpExchange exchange, final Health health) throws IOException {
        respond(exchange, health.up() ? 200 : 503, "text/plain; charset=utf-8",
                (health.up() ? "ok" : "unavailable") + ": " + health.detail() + "\n");
    }

    private static void respond(final HttpExchange exchange, final int status,
                                final String contentType, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
