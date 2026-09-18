/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.riptide.utils.HttpServerConfig;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedHttpReadTest {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();

    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/ok", exchange -> {
                try (exchange) {
                    AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    final byte[] answer = "hello".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.createContext("/missing", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            server.createContext("/broken", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(500, -1);
                }
            });
            server.createContext("/huge", exchange -> {
                try (exchange) {
                    final byte[] answer = new byte[4096];
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

    private static URL url(final String path) throws IOException {
        return URI.create("http://" + SERVER.getAddress().getHostString()
                + ":" + SERVER.getAddress().getPort() + path).toURL();
    }

    private static BoundedHttpRead read(final int maxBytes, final Map<String, String> headers) {
        return new BoundedHttpRead(Duration.ofSeconds(5), maxBytes, "document", () -> "the endpoint", () -> headers);
    }

    @Test
    void readsTheBodyAndSendsTheConfiguredHeaders() throws IOException {
        final byte[] body = read(1024, Map.of("Authorization", "Token secret")).readRemote(url("/ok"));

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(AUTHORIZATION.get()).isEqualTo("Token secret");
    }

    @Test
    void aNotFoundIsAbsenceRatherThanFailure() {
        assertThatThrownBy(() -> read(1024, Map.of()).readRemote(url("/missing")))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    void anyOtherNonOkStatusFailsNamingTheCode() {
        assertThatThrownBy(() -> read(1024, Map.of()).readRemote(url("/broken")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }

    @Test
    void aBodyPastTheCeilingIsRefusedNamingTheSubject() {
        assertThatThrownBy(() -> read(1024, Map.of()).readRemote(url("/huge")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ceiling for a document");
    }
}
