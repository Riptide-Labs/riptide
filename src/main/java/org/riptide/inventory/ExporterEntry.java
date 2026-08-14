/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddressString;

/**
 * One built enrichment entry from the exporters tree. The map key is the exporter
 * name, which is the value stamped as {@code exporterName}. Interface pins and the
 * full pin semantics land with the exporters-tree story (2.7).
 *
 * @param name the entry key, stamped on flows once the cutover story lands
 * @param address the single host address the entry matches
 * @param observationDomain the optional observation-domain pin, {@code null} for
 *         wildcard
 */
public record ExporterEntry(String name, IPAddressString address, Long observationDomain) {
}
