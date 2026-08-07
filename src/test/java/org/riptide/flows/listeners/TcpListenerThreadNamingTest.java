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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener's threads must be attributable to it in a dump.
 *
 * <p>Before this, {@code TcpListener} built both of its groups with no thread factory, so its
 * threads carried Netty's default {@code nioEventLoopGroup-N-M} — which names neither the listener
 * nor the thread's role. With several listeners configured, a dump could not tell you which one was
 * busy.
 *
 * <p>Scope: only the boss thread is asserted. Netty starts an event loop's carrier thread lazily,
 * on first registration, so the worker group has no live thread until a connection is accepted;
 * covering it would mean discovering the ephemeral port (which {@code TcpListener} does not expose)
 * and connecting. The worker factory is applied at the same construction site.
 */
class TcpListenerThreadNamingTest {

    @Test
    void bossThreadIsNamedAfterTheListenerAndItsRole() {
        final var existingThreads = liveThreadNames().collect(java.util.stream.Collectors.toSet());

        final var listener = new TcpListener("naming", parser(), new MetricRegistry())
                .withHost("127.0.0.1")
                .withPort(0);

        listener.start();
        try {
            final var newThreads = liveThreadNames()
                    .filter(name -> !existingThreads.contains(name))
                    .toList();

            assertThat(newThreads)
                    .as("the accept thread must identify its listener and role")
                    .anyMatch(name -> name.startsWith("tcp-listener-nio-boss-naming-"));

            assertThat(newThreads)
                    .as("no group may fall back to Netty's anonymous default naming")
                    .noneMatch(name -> name.startsWith("nioEventLoopGroup-"));
        } finally {
            listener.stop();
        }
    }

    @Test
    void handlesPercentInListenerName() {
        final var listener = new TcpListener("listener%1", parser(), new MetricRegistry())
                .withHost("127.0.0.1")
                .withPort(0);

        listener.start();
        try {
            assertThat(liveThreadNames())
                    .anyMatch(name -> name.startsWith("tcp-listener-nio-boss-listener%1-"));
        } finally {
            listener.stop();
        }
    }

    private static java.util.stream.Stream<String> liveThreadNames() {
        return Thread.getAllStackTraces().keySet().stream().map(Thread::getName);
    }

    private static TcpParser parser() {
        return new TcpParser() {
            @Override
            public Handler accept(final InetSocketAddress remoteAddress, final InetSocketAddress localAddress) {
                return new Handler() {
                    @Override
                    public void inactive() {
                    }

                    @Override
                    public void active() {
                    }

                    @Override
                    public Optional<CompletableFuture<?>> parse(final Instant receivedAt, final ByteBuf buffer) {
                        return Optional.empty();
                    }
                };
            }

            @Override
            public String getName() {
                return "naming";
            }

            @Override
            public String getDescription() {
                return "naming";
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
