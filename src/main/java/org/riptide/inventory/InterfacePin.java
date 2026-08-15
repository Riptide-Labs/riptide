/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/**
 * A static interface fact an operator pins for one ifIndex of one exporter, the
 * enrichment ladder's middle rung: a pinned field wins over whatever a walk or
 * exporter-pushed option data reports, and every field left null falls through to
 * the rungs below. Setting one field and leaving the rest unset is the normal case.
 *
 * <p>Inventory-owned rather than the snmp package's {@code IfInfo}, so this package
 * imports neither consumer (AD-10), the same reason {@link CredentialVersion} exists
 * instead of reaching for the snmp version enum. The consumer converts a pin into
 * whatever the ladder wants when it cuts over; keeping the conversion on that side
 * is what lets the legacy classes be deleted later without touching inventory.</p>
 *
 * @param name the short interface name, e.g. {@code Eth1/0}
 * @param alias the operator-assigned label, stable across reboots (RFC 2863)
 * @param highSpeed the interface speed in Mbit/s
 */
public record InterfacePin(String name, String alias, Long highSpeed) {
}
