/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.riptide.secrets.SecretResolvers;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SnmpUtils {

    // IF-MIB (RFC 2863): ifTable ifDescr; ifXTable ifName / ifHighSpeed (Mbit/s) / ifAlias
    private static final OID IF_DESCR = new OID("1.3.6.1.2.1.2.2.1.2");
    private static final OID IFX_NAME = new OID("1.3.6.1.2.1.31.1.1.1.1");
    private static final OID IFX_HIGH_SPEED = new OID("1.3.6.1.2.1.31.1.1.1.15");
    private static final OID IFX_ALIAS = new OID("1.3.6.1.2.1.31.1.1.1.18");

    private SnmpUtils() {
    }

    enum WalkOutcome {
        OK, TIMEOUT, ERROR
    }

    public record WalkResult(Map<Integer, IfInfo> rows, WalkOutcome outcome) {
    }

    private static WalkResult walkColumns(final Snmp snmp, final Target<?> target, final SnmpEndpoint snmpEndpoint,
                                          final OID[] columns, final Function<List<VariableBinding>, IfInfo> row) {
        final TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
        final List<TableEvent> tableEvents = tableUtils.getTable(target, columns, null, null);

        final Map<Integer, IfInfo> interfaces = new TreeMap<>();

        for (final TableEvent tableEvent : tableEvents) {
            if (tableEvent.isError()) {
                // The SNMP4J target must not be logged: its toString() carries the credential (#335)
                log.warn("Error querying {} for {}: {}", columns[0], snmpEndpoint, tableEvent.getErrorMessage());
                // rows collected before the error are discarded: an incomplete table must not
                // be cached as if it were complete
                final var outcome = tableEvent.getStatus() == TableEvent.STATUS_TIMEOUT
                        ? WalkOutcome.TIMEOUT
                        : WalkOutcome.ERROR;
                return new WalkResult(new TreeMap<>(), outcome);
            }
            if (tableEvent.getIndex() == null || tableEvent.getColumns() == null) {
                continue;
            }

            final int ifIndex = tableEvent.getIndex().last();
            // Arrays.asList, not List.of: sparse tables leave null entries for missing columns
            final IfInfo ifInfo = row.apply(Arrays.asList(tableEvent.getColumns()));
            if (ifInfo != null) {
                interfaces.put(ifIndex, ifInfo);
            }
        }

        return new WalkResult(interfaces, WalkOutcome.OK);
    }

    private static IfInfo ifXRow(final List<VariableBinding> columns) {
        final String name = string(columns.get(0));
        if (name == null) {
            return null;
        }
        return new IfInfo(name, string(columns.get(2)), number(columns.get(1)));
    }

    private static IfInfo ifRow(final List<VariableBinding> columns) {
        final String descr = string(columns.get(0));
        return descr != null ? new IfInfo(descr, null, null) : null;
    }

    private static String string(final VariableBinding vb) {
        // isException: noSuchObject/noSuchInstance/endOfMibView must not leak as literal strings
        if (vb == null || vb.getVariable() == null || vb.getVariable().isException() || vb.getVariable().toString().isEmpty()) {
            return null;
        }
        return vb.getVariable().toString();
    }

    private static Long number(final VariableBinding vb) {
        if (vb == null || vb.getVariable() == null || vb.getVariable().isException()) {
            return null;
        }
        return vb.getVariable().toLong();
    }

    /**
     * Walks the exporter's interface table: ifXTable (ifName/ifHighSpeed/ifAlias) first,
     * falling back to the legacy ifTable (ifDescr only) — unless the first walk timed out.
     */
    public static WalkResult getIfInfoMap(final SnmpEndpoint snmpEndpoint, final SecretResolvers secretResolvers) throws IOException {
        final SnmpBuilder snmpBuilder = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getSnmpBuilder();
        try (Snmp snmp = snmpBuilder.build()) {
            final Target<?> target = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getTarget(snmp, snmpBuilder, snmpEndpoint, secretResolvers);
            final var ifXTable = walkColumns(snmp, target, snmpEndpoint, new OID[]{IFX_NAME, IFX_HIGH_SPEED, IFX_ALIAS}, SnmpUtils::ifXRow);
            if (shouldFallback(ifXTable)) {
                return walkColumns(snmp, target, snmpEndpoint, new OID[]{IF_DESCR}, SnmpUtils::ifRow);
            }
            return ifXTable;
        }
    }

    /**
     * The fallback exists for agents that lack ifXTable: v2c/v3 agents answer clean-empty (OK),
     * v1 agents answer with a noSuchName error (ERROR). A TIMEOUT means the agent does not
     * answer at all — the fallback walk would only time out again (#337).
     */
    static boolean shouldFallback(final WalkResult ifXTableResult) {
        return switch (ifXTableResult.outcome()) {
            case TIMEOUT -> false;
            case ERROR -> true;
            case OK -> ifXTableResult.rows().isEmpty();
        };
    }
}
