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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryClientTest {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();

    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/devices", exchange -> {
                try (exchange) {
                    AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    final byte[] answer = "[]".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.createContext("/gone", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(404, -1);
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

    private static String endpoint(final String path) {
        return "http://" + SERVER.getAddress().getHostString()
                + ":" + SERVER.getAddress().getPort() + path;
    }

    private static DiscoveryClient client(final String path, final String token) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl(endpoint(path));
        config.setToken(SecretRef.of(token));
        return new DiscoveryClient(config, SecretResolvers.defaults());
    }

    @Test
    void sendsTheTokenWithTheConfiguredScheme() throws IOException {
        final byte[] body = client("/devices", "s3cret").fetch();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("[]");
        assertThat(AUTHORIZATION.get())
                .as("NetBox wants 'Token', not 'Bearer'")
                .isEqualTo("Token s3cret");
    }

    @Test
    void sendsNoAuthorizationHeaderWhenNoTokenIsConfigured() throws IOException {
        AUTHORIZATION.set(null);

        client("/devices", null).fetch();

        assertThat(AUTHORIZATION.get()).isNull();
    }

    @Test
    void aNotFoundArrivesAsAbsenceRatherThanFailure() {
        assertThatThrownBy(() -> client("/gone", null).fetch())
                .isInstanceOf(FileNotFoundException.class);
    }

    /**
     * The exported-but-empty variable the URL gate goes to lengths to defend against, one key over.
     * Unvalidated it sent {@code Authorization: " <token>"}, NetBox answered 403, and the operator
     * saw only "answered HTTP 403, not 200" with nothing naming the scheme.
     */
    @Test
    void aBlankAuthSchemeIsRefusedAtConstructionNamingTheKey() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl(endpoint("/devices"));
        config.setToken(SecretRef.of("s3cret"));
        config.setAuthScheme("");

        assertThatThrownBy(() -> new DiscoveryClient(config, SecretResolvers.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.auth-scheme");
    }

    /**
     * The other side of the same trap. With no token the scheme is never read, so refusing a blank
     * one would kill a collector over a key that does nothing — trading one exported-but-empty
     * startup failure for another, which is what the refusal above exists to remove. The gate is
     * therefore the token, the same condition that decides whether the header is built at all.
     */
    @Test
    void aBlankAuthSchemeWithNoTokenIsHarmlessAndSendsNoHeader() throws IOException {
        AUTHORIZATION.set(null);
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl(endpoint("/devices"));
        config.setAuthScheme("");

        new DiscoveryClient(config, SecretResolvers.defaults()).fetch();

        assertThat(AUTHORIZATION.get()).isNull();
    }

    @Test
    void describeRedactsCredentialsEmbeddedInTheUrl() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://user:hunter2@netbox.example.com/api/devices/");

        final String described = new DiscoveryClient(config, SecretResolvers.defaults()).describe();

        assertThat(described).doesNotContain("hunter2").contains("***@");
    }
}
