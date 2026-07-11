/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import lombok.Data;
import org.riptide.snmp.SnmpDefinition;

/**
 * A configured node: how to <em>match</em> an exporter (subnet, optionally pinned to one
 * observation domain) and how to <em>talk</em> to it (optional SNMP agent config).
 *
 * <p>Deliberately thin — interface metadata, hostnames, and other enrichment results live in
 * TTL caches keyed by the matched node, never on the node itself.</p>
 */
@Data
public class NodeDefinition {

    /** Optional human-readable name; defaults to the subnet when unset. */
    private String label;

    private IPAddressString subnetAddress;

    /** Restricts this node to one observation domain / source ID; {@code null} matches any. */
    private Long observationDomain;

    /** SNMP agent configuration; {@code null} if this node is not polled. */
    private SnmpDefinition snmp;

    public String label() {
        return this.label != null ? this.label : String.valueOf(this.subnetAddress);
    }
}
