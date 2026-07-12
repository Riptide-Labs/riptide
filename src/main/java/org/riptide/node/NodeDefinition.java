/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import lombok.Data;
import org.riptide.snmp.IfInfo;
import org.riptide.snmp.SnmpDefinition;

import java.util.HashMap;
import java.util.Map;

/**
 * A configured node: how to <em>match</em> an exporter (subnet, optionally pinned to one
 * observation domain) and how to <em>talk</em> to it (optional SNMP agent config). The
 * node's name is its key in the {@code riptide.nodes} map.
 *
 * <p>Deliberately thin — interface metadata, hostnames, and other enrichment results live in
 * TTL caches keyed by the matched node, never on the node itself.</p>
 */
@Data
public class NodeDefinition {

    private IPAddressString subnetAddress;

    /** Restricts this node to one observation domain / source ID; {@code null} matches any. */
    private Long observationDomain;

    /** SNMP agent configuration; {@code null} if this node is not polled. */
    private SnmpDefinition snmp;

    /**
     * Static interface mapping (ifIndex → name/alias/high-speed) — the enrichment
     * ladder's middle rung. Fields set here pin over live SNMP values; SNMP fills the
     * rest. Works without any {@code snmp} block.
     */
    private Map<Integer, IfInfo> interfaces = new HashMap<>();
}
