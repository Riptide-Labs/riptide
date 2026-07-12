/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

/**
 * Resolved IF-MIB data for one interface. {@code alias} (ifAlias) is the operator-assigned
 * label and — unlike ifIndex — non-volatile across device reboots (RFC 2863), making it the
 * preferred stable label. {@code highSpeed} is ifHighSpeed in Mbit/s. Both are {@code null}
 * when the agent only serves the legacy ifTable ({@code name} is ifDescr in that case).
 */
public record IfInfo(String name, String alias, Long highSpeed) {

    /**
     * Per-field pin: fields of {@code pinned} (the operator's static mapping) win where
     * present; {@code fallback} (live SNMP) fills the rest. Either side may be
     * {@code null}.
     */
    public static IfInfo merge(final IfInfo pinned, final IfInfo fallback) {
        if (pinned == null) {
            return fallback;
        }
        if (fallback == null) {
            return pinned;
        }
        return new IfInfo(
                pinned.name != null ? pinned.name : fallback.name,
                pinned.alias != null ? pinned.alias : fallback.alias,
                pinned.highSpeed != null ? pinned.highSpeed : fallback.highSpeed);
    }
}
