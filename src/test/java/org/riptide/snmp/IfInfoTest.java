/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class IfInfoTest {

    @Test
    public void pinnedFieldsWinPerField() {
        final IfInfo pinned = new IfInfo(null, "Uplink to AS64500", null);
        final IfInfo live = new IfInfo("eth0", "tmp-patched", 10000L);

        final IfInfo merged = IfInfo.merge(pinned, live);

        assertThat(merged.name()).isEqualTo("eth0");                  // live fills
        assertThat(merged.alias()).isEqualTo("Uplink to AS64500");    // pinned wins
        assertThat(merged.highSpeed()).isEqualTo(10000L);             // live fills
    }

    @Test
    public void fileOnlyAndSnmpOnlyPassThrough() {
        final IfInfo only = new IfInfo("eth0", null, null);

        assertThat(IfInfo.merge(only, null)).isEqualTo(only);
        assertThat(IfInfo.merge(null, only)).isEqualTo(only);
    }

    @Test
    public void neitherYieldsNull() {
        assertThat(IfInfo.merge(null, null)).isNull();
    }

    @Test
    public void fullyPinnedIgnoresLive() {
        final IfInfo pinned = new IfInfo("eth9", "pinned", 42L);
        final IfInfo live = new IfInfo("eth0", "live", 10000L);

        assertThat(IfInfo.merge(pinned, live)).isEqualTo(pinned);
    }
}
