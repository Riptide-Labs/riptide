/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolver;
import org.riptide.secrets.SecretResolvers;
import org.riptide.utils.HttpServerConfig;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * A rotated token reaches the next fetch, with no restart (#804).
     *
     * <p>The reason this is not merely nice: a {@code file://} reference is re-read on every use for
     * a device credential, so an operator rotating a file-backed discovery token reasonably expects
     * the same. Resolved once at construction, it silently did not, and nothing said so.</p>
     */
    @Test
    void aRotatedTokenReachesTheNextFetchWithoutARestart() throws Exception {
        final Path secret = Files.createTempFile("discovery-token", ".txt");
        secret.toFile().deleteOnExit();
        Files.writeString(secret, "first-token");
        final DiscoveryClient client = fileBacked(secret);

        client.fetch();
        assertThat(AUTHORIZATION.get()).isEqualTo("Token first-token");

        Files.writeString(secret, "rotated-token");
        client.fetch();

        assertThat(AUTHORIZATION.get())
                .as("the same client, no restart, the new value")
                .isEqualTo("Token rotated-token");
    }

    /**
     * The security-relevant half. A reference that stops resolving must fail the read, never fall
     * through to an unauthenticated one: an endpoint that answers 200 to a request with no token
     * would hand back whatever an anonymous caller may see, and every guard downstream would treat
     * that fleet as a legitimate change to the inventory.
     */
    @Test
    void aTokenThatStopsResolvingFailsTheReadRatherThanSendingNone() throws Exception {
        final Path secret = Files.createTempFile("discovery-token-vanishing", ".txt");
        Files.writeString(secret, "present-for-now");
        final DiscoveryClient client = fileBacked(secret);
        client.fetch();

        Files.delete(secret);
        AUTHORIZATION.set("not-overwritten");

        assertThatThrownBy(client::fetch)
                .as("the poll fails, and the caller counts it")
                .isInstanceOf(RuntimeException.class);
        assertThat(AUTHORIZATION.get())
                .as("no request reached the endpoint at all, so none reached it unauthenticated")
                .isEqualTo("not-overwritten");
    }

    /**
     * Once per read, not once per header or once per retry. {@code vault://} is the one scheme with
     * a remote cost and it is charged per call, so this bounds what per-read resolution costs.
     */
    @Test
    void theTokenIsResolvedOncePerFetch() throws Exception {
        final AtomicInteger resolutions = new AtomicInteger();
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl(endpoint("/devices"));
        config.setToken(SecretRef.of("counted"));
        final SecretResolvers counting = new SecretResolvers(List.of(new SecretResolver() {
            @Override
            public String scheme() {
                return "plain";
            }

            @Override
            public String resolve(final SecretRef ref) {
                resolutions.incrementAndGet();
                return "counted";
            }
        }));
        final DiscoveryClient client = new DiscoveryClient(config, counting);
        final int atConstruction = resolutions.get();

        client.fetch();

        assertThat(resolutions.get() - atConstruction)
                .as("one read, one resolution")
                .isEqualTo(1);
    }

    private static DiscoveryClient fileBacked(final Path secret) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl(endpoint("/devices"));
        config.setToken(SecretRef.of("file://" + secret));
        return new DiscoveryClient(config, SecretResolvers.defaults());
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
