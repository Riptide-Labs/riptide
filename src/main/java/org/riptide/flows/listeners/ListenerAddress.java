/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.listeners;

import io.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;

/**
 * Renders where a listener listens, for the startup log and diagnostics.
 *
 * <p>Host from configuration, port from the socket. Those are the halves each source answers best:
 * the operator wrote the host and can grep for it, while the port is the half the kernel may choose
 * — {@code port: 0} means the configured value says nothing about where the socket actually is.
 *
 * <p>Reading the host back off the channel instead looks more truthful and reads far worse. A
 * wildcard bind reports {@code 0:0:0:0:0:0:0:0} on a dual-stack JVM rather than the {@code *} the
 * operator configured, and a configured hostname comes back as its resolved literal, so the log no
 * longer contains the string that is in the config file.
 *
 * <p>IPv6 hosts are bracketed, so address and port stay distinguishable: an unbracketed
 * {@code ::1:4739} leaves the reader counting colons.
 */
final class ListenerAddress {

    private ListenerAddress() {
    }

    /**
     * @param socketFuture the bind future, or {@code null} before {@code start()}; the bound port is
     *                     used only while its channel is active, so a stopped listener stops
     *                     claiming a port another process may since have taken — the same
     *                     misattribution {@code UdpListener.stop()} avoids by deregistering its
     *                     {@code socketDrops} gauge before releasing the socket
     * @param host         the configured host, or {@code null} for a wildcard bind
     * @param port         the configured port, reported when nothing is bound
     */
    static String describe(final ChannelFuture socketFuture, final String host, final int port) {
        int effectivePort = port;
        if (socketFuture != null
                && socketFuture.channel().isActive()
                && socketFuture.channel().localAddress() instanceof InetSocketAddress bound) {
            effectivePort = bound.getPort();
        }
        final var rendered = host != null ? host : "*";
        return (rendered.indexOf(':') >= 0 ? "[" + rendered + "]" : rendered) + ":" + effectivePort;
    }
}
