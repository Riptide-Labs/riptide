/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/**
 * The SNMP protocol version a credential set speaks. Inventory-owned so this
 * package does not reach into {@code org.riptide.snmp} (AD-10); the endpoint
 * factory on the snmp side maps these onto its own version machinery. Spring
 * binds enum values case-insensitively, so {@code version: v3} works as spelled
 * in configuration.
 */
public enum CredentialVersion {
    V1,
    V2C,
    V3
}
