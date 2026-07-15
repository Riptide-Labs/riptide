/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.riptide.flows.Daemon;
import org.riptide.flows.listeners.Listener;

import java.net.ServerSocket;
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

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop();
        }
    }

    private int start(final Daemon daemon) throws Exception {
        final int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        final var properties = new RiptideManagementProperties();
        properties.setPort(port);
        properties.setBindAddress("127.0.0.1");

        this.server = new ManagementServer(properties, new HealthService(daemon));
        this.server.start();
        return port;
    }

    private int status(final int port, final String path) throws Exception {
        final HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
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
}
