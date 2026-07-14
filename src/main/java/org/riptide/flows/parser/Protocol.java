/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import java.util.Objects;

public enum Protocol {
    NETFLOW5(org.riptide.flows.parser.netflow5.proto.Header.VERSION, "Netflow v5"),
    NETFLOW9(org.riptide.flows.parser.netflow9.proto.Header.VERSION, "Netflow v9"),
    IPFIX(org.riptide.flows.parser.ipfix.proto.Header.VERSION, "IPFix"),
    SFLOW(org.riptide.flows.parser.sflow.proto.Datagram.VERSION, "sFlow v5");

    public final int version;
    public final String description;

    Protocol(final int version, final String description) {
        this.version = version;
        this.description = Objects.requireNonNull(description);
    }
}
