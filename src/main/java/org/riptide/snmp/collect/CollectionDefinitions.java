/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp.collect;

import org.snmp4j.smi.OID;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.riptide.snmp.collect.CollectionDefinition.ColumnType.COUNTER64;
import static org.riptide.snmp.collect.CollectionDefinition.ColumnType.GAUGE;
import static org.riptide.snmp.collect.CollectionDefinition.ColumnType.INFO;

/** The built-in definitions a polling profile may name in {@code collect}. */
public final class CollectionDefinitions {

    private static final String IFX = "1.3.6.1.2.1.31.1.1.1.";
    private static final String IF = "1.3.6.1.2.1.2.2.1.";

    /** IF-MIB ifXTable counters plus the three enrichment columns, snmp_exporter naming. */
    public static final CollectionDefinition IF_MIB_INTERFACES = new CollectionDefinition(
            "if-mib-interfaces", "ifIndex", List.of(
                    col(IFX + "1", "ifName", INFO),
                    col(IFX + "18", "ifAlias", INFO),
                    col(IFX + "15", "ifHighSpeed", INFO),
                    col(IFX + "6", "ifHCInOctets", COUNTER64),
                    col(IFX + "10", "ifHCOutOctets", COUNTER64),
                    col(IFX + "7", "ifHCInUcastPkts", COUNTER64),
                    col(IFX + "11", "ifHCOutUcastPkts", COUNTER64),
                    col(IFX + "8", "ifHCInMulticastPkts", COUNTER64),
                    col(IFX + "12", "ifHCOutMulticastPkts", COUNTER64),
                    col(IFX + "9", "ifHCInBroadcastPkts", COUNTER64),
                    col(IFX + "13", "ifHCOutBroadcastPkts", COUNTER64),
                    col(IF + "14", "ifInErrors", COUNTER64),
                    col(IF + "20", "ifOutErrors", COUNTER64),
                    col(IF + "13", "ifInDiscards", COUNTER64),
                    col(IF + "19", "ifOutDiscards", COUNTER64),
                    col(IF + "8", "ifOperStatus", GAUGE),
                    col(IF + "7", "ifAdminStatus", GAUGE)),
            5);

    private static final Map<String, CollectionDefinition> BY_NAME = Map.of(
            IF_MIB_INTERFACES.name(), IF_MIB_INTERFACES);

    private CollectionDefinitions() {
    }

    public static Optional<CollectionDefinition> byName(final String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    public static Set<String> names() {
        return new TreeSet<>(BY_NAME.keySet());
    }

    private static CollectionDefinition.Column col(final String oid, final String metric,
                                                   final CollectionDefinition.ColumnType type) {
        return new CollectionDefinition.Column(new OID(oid), metric, type);
    }
}
