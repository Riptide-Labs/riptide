/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import org.riptide.snmp.SnmpEndpoint;

import java.util.Optional;

/**
 * A matched node: the {@link NodeDefinition} an exporter identity resolved to, bound to the
 * concrete exporter address of the flow.
 */
public record Node(NodeDefinition definition, IPAddressString address) {

    public String label() {
        return this.definition.label();
    }

    /** The SNMP endpoint to poll this node, if it has SNMP agent configuration. */
    public Optional<SnmpEndpoint> snmpEndpoint() {
        return Optional.ofNullable(this.definition.getSnmp())
                .map(snmp -> snmp.createEndpoint(this.address));
    }
}
