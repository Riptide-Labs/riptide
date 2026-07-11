/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.net.InetAddress;

// TODO fooker: Do we need to source by SocketAddr?
@Getter
@Setter
@AllArgsConstructor
public class Source {
    @NonNull
    private final String location;

    @NonNull
    private final InetAddress exporterAddr;

    /** Observation domain (IPFIX) / source ID (NetFlow v9); {@code 0} when the protocol has none. */
    private final long observationDomain;

    public Source(final String location, final InetAddress exporterAddr) {
        this(location, exporterAddr, 0);
    }

    public ExporterIdentity identity() {
        return new ExporterIdentity.NetflowIpfix(this.exporterAddr, this.observationDomain);
    }
}
