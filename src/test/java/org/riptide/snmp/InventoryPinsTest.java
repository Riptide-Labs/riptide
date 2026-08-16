/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.ExporterEntry;
import org.riptide.inventory.InterfacePin;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryPinsTest {

    private static ExporterEntry entry(final Map<Integer, InterfacePin> pins) {
        return new ExporterEntry("core-router", new IPAddressString("10.0.0.1"), null, pins);
    }

    @Test
    void everyFieldLandsOnItsOwnField() {
        // InterfacePin and IfInfo carry the same three components in the same order, so a
        // transposition compiles and mislabels every pinned interface. Distinct values per
        // field are what make this test able to fail at all
        final var pins = entry(Map.of(7, new InterfacePin("Eth1/0", "Uplink to AS64500", 10_000L)));

        final IfInfo pinned = InventoryPins.pinFor(pins, 7);

        assertThat(pinned.name()).isEqualTo("Eth1/0");
        assertThat(pinned.alias()).isEqualTo("Uplink to AS64500");
        assertThat(pinned.highSpeed()).isEqualTo(10_000L);
    }

    @Test
    void unsetFieldsStayNullSoTheLadderFallsThroughPerField() {
        final var pins = entry(Map.of(7, new InterfacePin(null, "Only an alias", null)));

        final IfInfo pinned = InventoryPins.pinFor(pins, 7);

        assertThat(pinned.name()).isNull();
        assertThat(pinned.alias()).isEqualTo("Only an alias");
        assertThat(pinned.highSpeed()).isNull();
        // and the merge below it still fills the unpinned fields from the live rung
        final IfInfo live = new IfInfo("Eth1/0", "walked alias", 1_000L);
        final IfInfo merged = IfInfo.merge(pinned, live);
        assertThat(merged.name()).isEqualTo("Eth1/0");
        assertThat(merged.alias()).isEqualTo("Only an alias");
        assertThat(merged.highSpeed()).isEqualTo(1_000L);
    }

    @Test
    void anEntryThatPinsNothingThereYieldsNull() {
        assertThat(InventoryPins.pinFor(entry(Map.of(7, new InterfacePin("Eth1/0", null, null))), 9)).isNull();
        assertThat(InventoryPins.pinFor(entry(Map.of()), 7)).isNull();
        // no matching exporter entry at all is the common case and must not throw
        assertThat(InventoryPins.pinFor(null, 7)).isNull();
    }
}
