/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code socketDrops} gauge must live exactly as long as the socket it describes.
 *
 * <p>The regression this pins: {@code stop()} closing the socket but leaving the gauge registered.
 * The leaked closure holds the old bound address, so the moment any other process binds that port,
 * the dead listener publishes that socket's kernel drops as riptide ingest loss — the precise
 * misattribution reading {@code /proc/net/udp} per socket was chosen to avoid.
 */
class UdpListenerGaugeLifecycleTest {

    @Test
    void socketDropsGaugeIsRegisteredOnStartAndRemovedOnStop() {
        final var registry = new MetricRegistry();
        final var listener = new UdpListener("lifecycle", parser(), registry)
                .withHost("127.0.0.1")
                .withPort(0); // ephemeral: the gauge must attribute to the port actually bound
        final String gauge = MetricRegistry.name("listeners", "lifecycle", "socketDrops");

        listener.start();
        try {
            assertThat(registry.getGauges()).containsKey(gauge);
        } finally {
            listener.stop();
        }

        assertThat(registry.getGauges())
                .as("a stopped listener must not keep publishing a closure over a port it no longer owns")
                .doesNotContainKey(gauge);
    }

    private static UdpParser parser() {
        return new UdpParser() {
            @Override
            public CompletableFuture<?> parse(final Instant receivedAt, final ByteBuf buffer,
                                              final InetSocketAddress remoteAddress,
                                              final InetSocketAddress localAddress) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String getName() {
                return "lifecycle";
            }

            @Override
            public String getDescription() {
                return "lifecycle";
            }

            @Override
            public Object dumpInternalState() {
                return null;
            }

            @Override
            public void start(final ScheduledExecutorService executorService) {
            }

            @Override
            public void stop() {
            }
        };
    }
}
