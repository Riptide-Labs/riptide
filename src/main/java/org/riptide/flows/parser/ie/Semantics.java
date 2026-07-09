/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie;

public enum Semantics {
    DEFAULT,
    QUANTITY,
    TOTAL_COUNTER,
    DELTA_COUNTER,
    IDENTIFIER,
    FLAGS,
    LIST,
    SNMP_COUNTER,
    SNMP_GAUGE,
}
