/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.riptide.profiling.RecordingScopes;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The listener's read handler runs under {@code stage=listener, component=<receiver>}. */
class UdpListenerProfilingLabelsTest {

    @AfterEach
    void shutTheGate() {
        RecordingScopes.uninstall();
    }

    @Test
    void theReadHandlerRunsUnderTheListenersLabels() throws Exception {
        final RecordingScopes recorder = RecordingScopes.install();
        final List<List<String>> seen = new CopyOnWriteArrayList<>();
        final var parsed = new CountDownLatch(3);
        final var listener = new UdpListener("flows", parser(recorder, seen, parsed), new MetricRegistry())
                .withHost("127.0.0.1")
                .withPort(0);
        listener.start();
        try {
            final String description = listener.getDescription();
            final int port = Integer.parseInt(description.substring(description.lastIndexOf(':') + 1));
            try (var socket = new DatagramSocket()) {
                for (int i = 0; i < 3; i++) {
                    socket.send(new DatagramPacket(new byte[] {1}, 1, InetAddress.getLoopbackAddress(), port));
                }
            }
            assertThat(parsed.await(10, TimeUnit.SECONDS)).as("three datagrams parsed").isTrue();
        } finally {
            listener.stop();
        }

        assertThat(seen).as("every parse ran inside the listener's scope")
                .hasSize(3)
                .allSatisfy(labels -> assertThat(labels).containsExactly("stage=listener", "component=flows"));
        assertThat(recorder.distinctLabelSets()).as("one label set for the listener").isEqualTo(1);
    }

    private static UdpParser parser(final RecordingScopes recorder, final List<List<String>> seen,
                                    final CountDownLatch parsed) {
        return new UdpParser() {
            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) {
                seen.add(recorder.active());
                parsed.countDown();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return "flows";
            }

            @Override
            public String getDescription() {
                return "flows";
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
