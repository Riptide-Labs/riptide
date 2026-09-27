/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import java.util.Locale;

/**
 * A collection a polling profile's {@code collect} list may name. Inventory-owned so this
 * package does not reach into {@code org.riptide.snmp} (AD-10); the endpoint factory on the
 * snmp side maps these onto collection definitions. Spring binds enum values leniently, so
 * {@code collect: [if-mib-interfaces]} works as spelled in configuration.
 */
public enum CollectionName {
    IF_MIB_INTERFACES;

    /** The kebab-case spelling used in configuration and error messages, e.g. {@code if-mib-interfaces}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
