/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRequest;
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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.classification.internal.ClassificationRulesTestSupport.await;
import static org.riptide.classification.internal.ClassificationRulesTestSupport.rules;

/**
 * What #55 actually asked for, and the one thing every other suite leaves unobserved: an
 * {@code http://} ruleset refreshing <em>on its own interval</em>.
 *
 * <p>Every other classification and reloader test here — including the twenty-odd rows of
 * {@link ClassificationRuleReloaderTest} that cover 404s, hung servers, oversized bodies
 * and credential redaction against this same kind of server — configures {@code 1h} and
 * then calls {@code poll()} by hand. That is the right way to test what a cycle
 * <em>does</em>, and it left "applies without a restart" resting entirely on the
 * configured interval reaching {@code scheduleWithFixedDelay}, with nothing observing the
 * result.</p>
 *
 * <p><b>This test's whole value is that it never calls {@code poll()}.</b> It boots the
 * real context, so the bean graph, the schedule and the socket are the production ones,
 * and it only changes what the server answers and waits. If someone ever "tidies" it by
 * driving a poll directly, the property is gone and the test still passes.</p>
 *
 * <p><b>Two ways a test like this passes for the wrong reason, both guarded.</b> It could
 * pass while nothing polls at all — an unchanged endpoint looks identical to an endpoint
 * nobody asked — so the server counts requests and the assertions require the count to
 * grow. And it could pass against <em>any</em> schedule rather than the configured one, so
 * the refresh has an upper bound derived from {@link #INTERVAL} rather than a bare
 * "eventually".</p>
 *
 * <p>{@code @DirtiesContext} matters here more than usual: without it the cached context
 * keeps a 200ms poll running under every later test class in the fork, fetching and
 * hashing forever. The context is closed first and the server stopped after, because the
 * other order leaves a live schedule counting connection-refused failures into unrelated
 * classes.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HttpRulesRefreshOnIntervalTest {

    /** Declared once and used for both the property and every wait budget below. */
    private static final Duration INTERVAL = Duration.ofMillis(200);

    /**
     * A pickup this slow means the schedule is not running at the configured interval. It
     * is 25x the interval, so it is nowhere near flaky, while still failing a trigger that
     * ignored the key and used a default of seconds.
     */
    private static final Duration PICKUP_CEILING =
            min(INTERVAL.multipliedBy(25), Duration.ofSeconds(10));

    /**
     * The ceiling is derived from the interval so it stays a real multiple of it, and
     * capped so it stays a real ceiling: without the cap, raising {@link #INTERVAL} would
     * quietly turn a five-second bound into an hours-long wait that only the JUnit timeout
     * ends, and the failure would name no property.
     */
    private static Duration min(final Duration a, final Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** What the server answers next. Volatile: the schedule thread reads what a test writes. */
    private static volatile String body = rules("alpha");

    /** Requests actually served, so "polled and skipped" is distinguishable from "never polled". */
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    private static final HttpServer SERVER = startServer();

    @Autowired
    private ClassificationEngine engine;

    @Autowired
    private MetricRegistry metrics;

    /**
     * Started from a static initialiser, because {@link DynamicPropertySource} is read
     * while the context is built and that happens before this class's own callbacks.
     *
     * <p>{@link HttpServerConfig#ensureApplied()} first, for the reason that class exists
     * (#545): {@code sun.net.httpserver.ServerConfig} latches its statics when the
     * <em>first</em> server in the process is created, and a static initialiser is about
     * the earliest a surefire fork can get there.</p>
     */
    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/rules.csv", exchange -> {
                try (exchange) {
                    REQUESTS.incrementAndGet();
                    final byte[] answer = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.start();
            return server;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not start the rules server", e);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @DynamicPropertySource
    static void rulesEndpoint(final DynamicPropertyRegistry registry) {
        registry.add("riptide.classification.rules",
                () -> "http://" + SERVER.getAddress().getHostString()
                        + ":" + SERVER.getAddress().getPort() + "/rules.csv");
        registry.add("riptide.classification.reload-interval", () -> INTERVAL.toMillis() + "ms");
    }

    /**
     * The acceptance criterion of #55: change what the endpoint serves, touch nothing else,
     * and classification follows within a bound the configured interval implies.
     */
    @Test
    @Timeout(60)
    void anHttpRulesetIsPickedUpWithoutARestartAndWithoutAManualPoll() throws Exception {
        assertThat(classification())
                .as("the boot load fetched the ruleset over HTTP")
                .isEqualTo("alpha");
        final long publishedBefore = successes().getCount();

        body = rules("beta");
        final long flipped = System.nanoTime();

        // the counter, not the classification: the engine publishes the tree before it
        // increments successes, and does so unsynchronised, so waiting on the answer can
        // return while the counter is still where it was
        await("the schedule to fetch and publish the new ruleset on its own",
                PICKUP_CEILING, () -> successes().getCount() > publishedBefore);

        assertThat(Duration.ofNanos(System.nanoTime() - flipped))
                .as("picked up on the configured interval, not on some other schedule")
                .isLessThan(PICKUP_CEILING);
        assertThat(classification())
                .as("and the published ruleset is the one the endpoint now serves")
                .isEqualTo("beta");
    }

    /**
     * The other half, and the reason the content hash exists: intervals keep elapsing
     * against an unchanged endpoint without rebuilding the decision tree.
     *
     * <p>Self-sufficient rather than relying on running after the test above — JUnit's
     * method order is unspecified, so an ordering premise stated in prose is not one the
     * suite enforces. It takes its own baselines and waits for observed requests instead
     * of sleeping, so the window is defined by polls that happened rather than by a
     * duration that may have covered none.</p>
     */
    @Test
    @Timeout(60)
    void anUnchangedEndpointIsPolledWithoutRebuilding() throws Exception {
        final String serving = classification();
        final long publishedBefore = successes().getCount();
        final long failedBefore = failures().getCount();
        final int requestsBefore = REQUESTS.get();

        await("several polls against an untouched endpoint", PICKUP_CEILING,
                () -> REQUESTS.get() >= requestsBefore + 3);

        assertThat(classification())
                .as("the same ruleset keeps classifying")
                .isEqualTo(serving);
        assertThat(successes().getCount())
                .as("an unchanged endpoint is fetched and skipped, never re-published")
                .isEqualTo(publishedBefore);
        assertThat(failures().getCount())
                .as("and polling an endpoint that is answering fine is not a failure")
                .isEqualTo(failedBefore);
        assertThat(this.metrics.getGauges().get("classification.reload.dead").getValue())
                .as("the schedule that served those requests is still alive")
                .isEqualTo(0);
    }

    private String classification() {
        return this.engine.classify(ClassificationRequest.builder().withDstPort(80).build());
    }

    /**
     * Read through {@code getCounters()} rather than {@code counter(name)}: the latter
     * creates a missing counter and hands back a zero, so a renamed metric would satisfy
     * every equality assertion above instead of failing.
     */
    private Counter successes() {
        return registered("classification.reload.successes");
    }

    private Counter failures() {
        return registered("classification.reload.failures");
    }

    private Counter registered(final String name) {
        final Counter counter = this.metrics.getCounters().get(name);
        assertThat(counter).as("%s is registered", name).isNotNull();
        return counter;
    }
}
