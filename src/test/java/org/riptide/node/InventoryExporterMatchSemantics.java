/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;

import java.util.List;
import java.util.Map;

/**
 * The semantics seam bound to the 0.9 inventory path, through the loader rather than
 * straight to the matcher. That is the point of this binding: the two existing ones
 * both start from an already-built entry, so the config-to-entry mapping (which key
 * becomes the name, which value becomes the match address, what shapes are accepted)
 * has never been inside the seam. Story 2.8 switches consumers onto
 * {@code ExporterView}, and this is what lets the characterisation suite follow them.
 *
 * <p>Every contract test is expressible here, with one mapping worth stating: the
 * contract's "entry without a subnet" case is an entry whose {@code address} key is
 * absent, which the loader rejects by naming the entry. Nothing is skipped. A skipped
 * contract test would be a silent coverage loss, which is the failure mode the
 * pre-cutover audit found in the older bindings.</p>
 */
final class InventoryExporterMatchSemantics implements ExporterMatchSemantics {

    private static final SnmpProfilesConfig NO_PROFILES = new SnmpProfilesConfig(Map.of(), Map.of());

    @Override
    public Matcher build(final List<Entry> entries) {
        final StringBuilder yaml = new StringBuilder("riptide:\n  exporters:\n");
        for (final Entry entry : entries) {
            yaml.append("    \"").append(entry.name()).append("\":\n");
            if (entry.subnet() != null) {
                yaml.append("      address: \"").append(entry.subnet()).append("\"\n");
            }
            if (entry.observationDomainPin() != null) {
                yaml.append("      observation-domain: ").append(entry.observationDomainPin()).append('\n');
            }
        }
        final InventorySnapshot snapshot = InventoryLoader.parse(NO_PROFILES, yaml.toString(), "contract.yaml");
        return identity -> snapshot.exporterView().match(identity).map(entry -> entry.name());
    }
}
