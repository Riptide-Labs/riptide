/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.riptide.telemetry.SecondsCounter;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code listeners.<name>.busySeconds}: the read loop's time inside the pipeline. */
class UdpListenerBusyTimeTest {

    private static final int DATAGRAMS = 50;
    private static final long PARSE_MILLIS = 10;

    @Test
    void timeSpentParsingOnTheReadLoopIsCounted() throws Exception {
        final var registry = new MetricRegistry();
        final var listener = new UdpListener("busy", slowParser(), registry)
                .withHost("127.0.0.1")
                .withPort(0);
        listener.start();
        try {
            final int port = boundPort(listener);
            try (var socket = new DatagramSocket()) {
                for (int i = 0; i < DATAGRAMS; i++) {
                    socket.send(new DatagramPacket(new byte[] {1}, 1, InetAddress.getLoopbackAddress(), port));
                }
            }
            final var received = registry.meter(MetricRegistry.name("listeners", "busy", "packetsReceived"));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            // received is marked before the parse, so also wait out the last datagram's parse
            while (received.getCount() < DATAGRAMS && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(PARSE_MILLIS * 3);
            assertThat(received.getCount()).as("every datagram read").isEqualTo(DATAGRAMS);

            // 50 parses of at least 10 ms each, all on the one read loop
            assertThat(busySeconds(registry)).isGreaterThanOrEqualTo(0.5d);
        } finally {
            listener.stop();
        }
    }

    @Test
    void busySecondsIsRegisteredOnStartAndRemovedOnStop() {
        final var registry = new MetricRegistry();
        final var listener = new UdpListener("busy", slowParser(), registry)
                .withHost("127.0.0.1")
                .withPort(0);
        final String name = MetricRegistry.name("listeners", "busy", "busySeconds");

        listener.start();
        try {
            assertThat(registry.getMetrics()).containsKey(name);
        } finally {
            listener.stop();
        }

        assertThat(registry.getMetrics()).doesNotContainKey(name);
    }

    private static double busySeconds(final MetricRegistry registry) {
        return ((SecondsCounter) registry.getMetrics()
                .get(MetricRegistry.name("listeners", "busy", "busySeconds"))).seconds();
    }

    /** The port actually bound, read from the description ("UDP 127.0.0.1:<port>"). */
    private static int boundPort(final UdpListener listener) {
        final String description = listener.getDescription();
        return Integer.parseInt(description.substring(description.lastIndexOf(':') + 1));
    }

    private static UdpParser slowParser() {
        return new UdpParser() {
            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) throws InterruptedException {
                Thread.sleep(PARSE_MILLIS);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return "busy";
            }

            @Override
            public String getDescription() {
                return "busy";
            }

            @Override
            public Object dumpInternalState() {
                return null;
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }
        };
    }
}
