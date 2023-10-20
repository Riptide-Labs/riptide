package org.riptide.flows.parser;

import java.util.Objects;

public enum Protocol {
    NETFLOW5(org.riptide.flows.parser.netflow5.proto.Header.VERSION, "Netflow v5"),
    NETFLOW9(org.riptide.flows.parser.netflow9.proto.Header.VERSION, "Netflow v9"),
    IPFIX(org.riptide.flows.parser.ipfix.proto.Header.VERSION, "IPFix");

    public final int version;
    public final String description;

    Protocol(final int version, final String description) {
        this.version = version;
        this.description = Objects.requireNonNull(description);
    }
}
