/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receive queue gauge read through a real listener on a real kernel: with the read loop held
 * inside the parser, datagrams pile up in the socket, and the gauge must see them against the
 * buffer the kernel granted. Linux only, because only Linux has {@code /proc/net/udp}.
 */
class UdpListenerReceiveQueueTest {

    @Test
    @EnabledOnOs(OS.LINUX)
    void aBlockedReadLoopLeavesBytesQueuedWithinTheGrantedBuffer() throws Exception {
        final var registry = new MetricRegistry();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var listener = new UdpListener("queued", blockingParser(entered, release), registry)
                .withHost("127.0.0.1")
                .withPort(0);
        listener.start();
        try {
            final String description = listener.getDescription();
            final int port = Integer.parseInt(description.substring(description.lastIndexOf(':') + 1));
            try (var socket = new DatagramSocket()) {
                final var loopback = InetAddress.getLoopbackAddress();
                socket.send(new DatagramPacket(new byte[512], 512, loopback, port));
                assertThat(entered.await(10, TimeUnit.SECONDS)).as("read loop holds the first datagram").isTrue();
                for (int i = 0; i < 20; i++) {
                    socket.send(new DatagramPacket(new byte[512], 512, loopback, port));
                }
            }

            final var queue = (Long) registry.getGauges()
                    .get(MetricRegistry.name("listeners", "queued", "receiveQueueBytes")).getValue();
            final var buffer = (Integer) registry.getGauges()
                    .get(MetricRegistry.name("listeners", "queued", "receiveBufferBytes")).getValue();
            assertThat(queue).as("20 datagrams wait behind the blocked loop").isGreaterThan(0L);
            assertThat(queue).as("the queue never exceeds the granted buffer").isLessThanOrEqualTo(buffer.longValue());
        } finally {
            release.countDown();
            listener.stop();
        }
    }

    private static UdpParser blockingParser(final CountDownLatch entered, final CountDownLatch release) {
        return new UdpParser() {
            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) throws InterruptedException {
                entered.countDown();
                release.await(20, TimeUnit.SECONDS);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return "queued";
            }

            @Override
            public String getDescription() {
                return "queued";
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
