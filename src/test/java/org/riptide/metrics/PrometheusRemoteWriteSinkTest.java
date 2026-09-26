/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import com.codahale.metrics.MetricRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.airlift.compress.v3.snappy.SnappyJavaDecompressor;
import org.junit.jupiter.api.Test;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.riptide.utils.HttpServerConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusRemoteWriteSinkTest {

    private static final String CONTEXT = "/insert/0:0/prometheus/api/v1/write";

    @Test
    void aBatchIsPostedWithTheRemoteWriteHeadersAndTheBearerToken() throws Exception {
        final var received = new LinkedBlockingQueue<Received>();
        try (var server = stub(exchange -> {
            received.add(Received.of(exchange));
            exchange.sendResponseHeaders(204, -1);
        })) {
            final var config = remoteWrite(server.url(), "plain-token");
            final var sink = new PrometheusRemoteWriteSink(config, SecretResolvers.defaults(), new OutboundHttpTrust(), new MetricRegistry());
            sink.start();
            sink.accept(List.of(new Sample("x", Map.of("a", "b"), 1d, 2L)));
            final Received r = received.poll(5, TimeUnit.SECONDS);
            sink.stop();

            assertThat(r).isNotNull();
            assertThat(r.path()).isEqualTo(CONTEXT);
            assertThat(r.headers()).containsEntry("Content-Type", "application/x-protobuf")
                    .containsEntry("Content-Encoding", "snappy")
                    .containsEntry("X-Prometheus-Remote-Write-Version", "0.1.0")
                    .containsEntry("Authorization", "Bearer plain-token");
            final byte[] plain = new byte[4096];
            final int n = new SnappyJavaDecompressor().decompress(r.body(), 0, r.body().length, plain, 0, plain.length);
            assertThat(n).isGreaterThan(0);
        }
    }

    @Test
    void aFiveHundredIsRetriedAndAFourHundredIsDroppedAndCounted() throws Exception {
        final var statuses = new LinkedBlockingQueue<>(List.of(503, 204, 400, 204));
        final var posts = new AtomicInteger();
        try (var server = stub(exchange -> {
            posts.incrementAndGet();
            exchange.sendResponseHeaders(statuses.poll(), -1);
        })) {
            final var metrics = new MetricRegistry();
            final var config = remoteWrite(server.url(), null);
            config.getBatch().setMaxSamples(1);
            config.setRetryBackoff(Duration.ofMillis(10));
            final var sink = new PrometheusRemoteWriteSink(config, SecretResolvers.defaults(), new OutboundHttpTrust(), metrics);
            sink.start();
            sink.accept(List.of(new Sample("a", Map.of(), 1d, 1L)));   // 503 then 204
            sink.accept(List.of(new Sample("b", Map.of(), 1d, 1L)));   // 400, dropped
            sink.accept(List.of(new Sample("c", Map.of(), 1d, 1L)));   // 204
            awaitPosts(posts, 4);
            sink.stop();

            assertThat(metrics.counter("metrics.sink.failedSamples").getCount()).isEqualTo(1);
            assertThat(metrics.counter("metrics.sink.sentSamples").getCount()).isEqualTo(2);
        }
    }

    @Test
    void aFullQueueDropsAndCountsInsteadOfBlocking() {
        final var metrics = new MetricRegistry();
        final var config = remoteWrite("http://127.0.0.1:9/api/v1/write", null);
        config.setQueueCapacity(2);
        final var sink = new PrometheusRemoteWriteSink(config, SecretResolvers.defaults(), new OutboundHttpTrust(), metrics);
        // not started: nothing drains
        sink.accept(List.of(sample(), sample(), sample(), sample()));
        assertThat(metrics.counter("metrics.sink.droppedSamples").getCount()).isEqualTo(2);
        assertThat(metrics.gauge("metrics.sink.queueDepth").getValue()).isEqualTo(2);
    }

    private static Sample sample() {
        return new Sample("x", Map.of(), 1d, 1L);
    }

    private static void awaitPosts(final AtomicInteger posts, final int expected) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (posts.get() < expected) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("expected " + expected + " posts, saw " + posts.get() + " within the deadline");
            }
            Thread.sleep(10);
        }
    }

    private static MetricsConfig.RemoteWrite remoteWrite(final String url, final String token) {
        final var config = new MetricsConfig.RemoteWrite();
        config.setUrl(url);
        config.setBearerToken(SecretRef.of(token));
        return config;
    }

    private record Received(String path, Map<String, String> headers, byte[] body) {
        static Received of(final HttpExchange exchange) throws IOException {
            final var headers = new HashMap<String, String>();
            // com.sun.net.httpserver.Headers stores request header names in its own normalized
            // case (single leading capital, everything else lowercase — "Content-type", not
            // "Content-Type"), regardless of how the client sent them. Re-canonicalize to the
            // conventional per-word capitalization so assertions can use the header names as
            // this sink actually sets them.
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(canonicalize(name), values.get(0)));
            try (InputStream in = exchange.getRequestBody()) {
                return new Received(exchange.getRequestURI().getPath(), headers, in.readAllBytes());
            }
        }

        private static String canonicalize(final String name) {
            final String[] words = name.split("-");
            final StringBuilder result = new StringBuilder(name.length());
            for (int i = 0; i < words.length; i++) {
                if (i > 0) {
                    result.append('-');
                }
                final String word = words[i];
                if (!word.isEmpty()) {
                    result.append(Character.toUpperCase(word.charAt(0)))
                            .append(word.substring(1).toLowerCase(Locale.ROOT));
                }
            }
            return result.toString();
        }
    }

    private interface RespondingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static Stub stub(final RespondingHandler handler) {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            final HttpHandler wrapped = exchange -> {
                try (exchange) {
                    handler.handle(exchange);
                }
            };
            server.createContext(CONTEXT, wrapped);
            server.start();
            return new Stub(server);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not start the test server", e);
        }
    }

    private record Stub(HttpServer server) implements AutoCloseable {
        String url() {
            return "http://" + this.server.getAddress().getHostString()
                    + ":" + this.server.getAddress().getPort() + CONTEXT;
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }
}
