/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddressString;

import java.util.Map;

/**
 * One built enrichment entry from the exporters tree. The map key is the exporter
 * name, which is the value stamped as {@code exporterName}.
 *
 * @param name the entry key, stamped on flows once the cutover story lands
 * @param address the single host address the entry matches
 * @param observationDomain the optional observation-domain pin, {@code null} for
 *         wildcard
 * @param interfaces static per-ifIndex pins, empty when the entry declares none;
 *         never {@code null}, and immutable, so a published snapshot cannot be
 *         edited behind the loader's back
 */
public record ExporterEntry(String name, IPAddressString address, Long observationDomain,
                            Map<Integer, InterfacePin> interfaces) {

    public ExporterEntry {
        interfaces = interfaces != null ? Map.copyOf(interfaces) : Map.of();
    }
}
