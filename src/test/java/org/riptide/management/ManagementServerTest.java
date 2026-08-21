/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.riptide.flows.Daemon;
import org.riptide.flows.listeners.Listener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real management HTTP server end to end. There is no ClickHouse anywhere in the setup,
 * which demonstrates the endpoints are ClickHouse-independent by construction.
 */
class ManagementServerTest {

    private ManagementServer server;
    private final MetricRegistry registry = new MetricRegistry();

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop();
        }
    }

    private int start(final Daemon daemon) throws Exception {
        return start(daemon, new RiptideManagementProperties().getMaxConcurrentRequests(), thread -> { });
    }

    private int start(final Daemon daemon, final java.util.function.Consumer<Thread> onHandle) throws Exception {
        return start(daemon, new RiptideManagementProperties().getMaxConcurrentRequests(), onHandle);
    }

    /**
     * {@code onHandle} runs on the handler thread before the health answer is produced, which is how
     * the tests observe which thread serves a request and how one holds a permit open.
     */
    private int start(final Daemon daemon,
                      final int maxConcurrentRequests,
                      final java.util.function.Consumer<Thread> onHandle) throws Exception {
        // port 0: the server binds an ephemeral port and reports it (#564). The previous
        // helper probed a free port with a ServerSocket, CLOSED it, and only then let the
        // server bind — leaving a window in which anything on the machine could take that
        // number, which showed up as intermittent BindException failures.
        final var properties = new RiptideManagementProperties();
        properties.setPort(0);
        properties.setBindAddress("127.0.0.1");
        properties.setMaxConcurrentRequests(maxConcurrentRequests);

        final var health = new HealthService(daemon) {
            @Override
            public Health liveness() {
                onHandle.accept(Thread.currentThread());
                return super.liveness();
            }

            @Override
            public Health readiness() {
                onHandle.accept(Thread.currentThread());
                return super.readiness();
            }
        };

        this.server = new ManagementServer(properties, health, this.registry);
        this.server.start();
        return this.server.getPort();
    }

    /**
     * Bounded deliberately: the concurrency-cap test parks a request to hold a permit, so without a
     * working cap the follow-up request would block forever. A timeout turns that into a fast
     * failure instead of a hung suite.
     */
    private int status(final int port, final String path) throws Exception {
        final HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private HttpResponse<String> get(final int port, final String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metricsEndpointServesTheRegistry() throws Exception {
        // registered before start so the handler renders whatever the registry holds at scrape time
        this.registry.meter(MetricRegistry.name("snmp", "walks")).mark(3);
        this.registry.counter(MetricRegistry.name("flows", "dropped")).inc(7);

        final Daemon daemon = mock(Daemon.class);
        lenient().when(daemon.isStarted()).thenReturn(true);
        final int port = start(daemon);

        final HttpResponse<String> response = get(port, "/metrics");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("text/plain; version=0.0.4; charset=utf-8");
        // registry names carry dots, which Prometheus does not allow in metric names
        assertThat(response.body())
                .contains("# TYPE snmp_walks counter")
                .contains("snmp_walks 3.0")
                .contains("# TYPE flows_dropped counter")
                .contains("flows_dropped 7.0");
    }

    /**
     * #545: stop() used to call stop(0) and shutdownNow(), which interrupts whatever is
     * mid-response — a probe in flight became a connection reset rather than an answer,
     * which a load balancer reads as a transport error during an orderly shutdown.
     */
    @Test
    void aRequestInFlightAtShutdownStillGetsItsResponse() throws Exception {
        final Daemon daemon = mock(Daemon.class);
        when(daemon.isStarted()).thenReturn(true);
        when(daemon.getListeners()).thenReturn(List.of());

        final var handlerEntered = new java.util.concurrent.CountDownLatch(1);
        final var releaseHandler = new java.util.concurrent.CountDownLatch(1);
        final int port = start(daemon, thread -> {
            handlerEntered.countDown();
            try {
                releaseHandler.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // dedicated threads, not commonPool: the sibling test below records why — a
        // supplyAsync queues behind whatever else the suite is running on the shared pool,
        // and under a saturated pool the request never even reaches the handler
        final var status = new java.util.concurrent.atomic.AtomicInteger();
        final Thread request = new Thread(() -> {
            try {
                status.set(get(port, "/livez").statusCode());
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }, "in-flight-request");
        request.start();
        assertThat(handlerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // the handler is released only once stop() is about to run: without this the
        // shutdown thread may not be scheduled first, the request completes normally, and
        // the test passes without ever exercising the in-flight-at-shutdown path — it would
        // pass against the old stop(0) code too
        final var shutdownEntered = new java.util.concurrent.CountDownLatch(1);
        final Thread shutdown = new Thread(() -> {
            shutdownEntered.countDown();
            this.server.stop();
        }, "shutdown");
        shutdown.start();
        assertThat(shutdownEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        releaseHandler.countDown();

        request.join(10_000);
        shutdown.join(10_000);
        assertThat(status.get()).as("the in-flight probe is answered, not reset").isEqualTo(200);
        this.server = null; // already stopped; keep tearDown from stopping it twice
    }

    @Test
    void metricsEndpointCanBeDisabledWithoutLosingProbes() throws Exception {
        final Daemon daemon = mock(Daemon.class);
        when(daemon.isStarted()).thenReturn(true);
        when(daemon.getListeners()).thenReturn(List.of());

        // port 0, read back after start — see the helper above (#564)
        final var properties = new RiptideManagementProperties();
        properties.setPort(0);
        properties.setBindAddress("127.0.0.1");
        properties.setMetricsEnabled(false);

        this.server = new ManagementServer(properties, new HealthService(daemon), this.registry);
        this.server.start();
        final int port = this.server.getPort();

        assertThat(status(port, "/livez")).isEqualTo(200);
        // no context registered, so the JDK server answers 404 rather than an empty scrape
        assertThat(status(port, "/metrics")).isEqualTo(404);
    }

    @Test
    void liveAndReadyWhenReceiversListening() throws Exception {
        final Listener listener = mock(Listener.class);
        when(listener.isListening()).thenReturn(true);
        final Daemon daemon = mock(Daemon.class);
        when(daemon.isStarted()).thenReturn(true);
        when(daemon.getListeners()).thenReturn(List.of(listener));

        final int port = start(daemon);
        assertThat(status(port, "/livez")).isEqualTo(200);
        assertThat(status(port, "/readyz")).isEqualTo(200);
    }

    @Test
    void unavailableWhenAReceiverDied() throws Exception {
        final Listener listener = mock(Listener.class);
        lenient().when(listener.getName()).thenReturn("ipfix");
        when(listener.isListening()).thenReturn(false);
        final Daemon daemon = mock(Daemon.class);
        when(daemon.isStarted()).thenReturn(true);
        when(daemon.getListeners()).thenReturn(List.of(listener));

        final int port = start(daemon);
        // a started receiver whose socket died is both not-live (restart) and not-ready
        assertThat(status(port, "/livez")).isEqualTo(503);
        assertThat(status(port, "/readyz")).isEqualTo(503);
    }

    @Test
    void liveButNotReadyWhileStarting() throws Exception {
        final Daemon daemon = mock(Daemon.class);
        when(daemon.isStarted()).thenReturn(false);

        final int port = start(daemon);
        assertThat(status(port, "/livez")).isEqualTo(200);   // booting is not a fatal state
        assertThat(status(port, "/readyz")).isEqualTo(503);  // not ready until receivers are up
    }

    /**
     * Pins the executor the handlers run on. Without this the suite passes byte-identically whether
     * the server uses virtual threads or the platform pool it replaced, so a revert or a typo in the
     * name prefix would ship green.
     */
    @Test
    void handlersRunOnNamedVirtualThreads() throws Exception {
        final Daemon daemon = mock(Daemon.class);
        when(daemon.isStarted()).thenReturn(true);
        when(daemon.getListeners()).thenReturn(List.of());
        final var observed = new java.util.concurrent.atomic.AtomicReference<Thread>();
        final int port = start(daemon, observed::set);

        assertThat(status(port, "/livez")).isEqualTo(200);

        final Thread handler = observed.get();
        assertThat(handler).isNotNull();
        assertThat(handler.isVirtual()).isTrue();
        assertThat(handler.getName()).startsWith("management-http-");
        // virtual threads are always daemon; the JVM is held up by Spring's keep-alive thread
        assertThat(handler.isDaemon()).isTrue();
    }

    /**
     * A thread-per-task executor has no ceiling of its own, so the cap is what stops a caller from
     * making the collector hold unbounded threads, exchanges and sockets. Beyond it, shed with 503.
     */
    @Test
    void shedsRequestsBeyondTheConcurrencyCap() throws Exception {
        final Daemon daemon = mock(Daemon.class);
        when(daemon.isStarted()).thenReturn(true);
        when(daemon.getListeners()).thenReturn(List.of());

        final var admitted = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        // one permit, and the single admitted request parked until we let it go
        final int port = start(daemon, 1, thread -> {
            admitted.countDown();
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // A dedicated thread, not commonPool: supplyAsync would queue behind whatever else the
        // suite is running on the shared pool, and this request has to actually reach the server
        // before the assertion below means anything.
        final var inFlight = new java.util.concurrent.CompletableFuture<Integer>();
        final Thread caller = new Thread(() -> {
            try {
                inFlight.complete(status(port, "/livez"));
            } catch (final Exception e) {
                inFlight.completeExceptionally(e);
            }
        }, "cap-test-caller");
        caller.setDaemon(true);
        caller.start();
        assertThat(admitted.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // the permit is taken, so this one is shed rather than queued
        assertThat(status(port, "/readyz")).isEqualTo(503);

        release.countDown();
        assertThat(inFlight.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
    }
}
