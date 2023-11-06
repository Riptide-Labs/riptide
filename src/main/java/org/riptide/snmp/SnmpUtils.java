package org.riptide.snmp;

import static org.riptide.snmp.SnmpUtils.Table.IfTable;
import static org.riptide.snmp.SnmpUtils.Table.IfXTable;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;

import lombok.Getter;

public final class SnmpUtils {
    private static final Logger LOG = LoggerFactory.getLogger(SnmpUtils.class);

    enum Table {
        // There are two tables that can be used to query for human-readable names for Snmp ifIndex numbers
        IfTable(new OID(".1.3.6.1.2.1.2.2.1"), 2),
        IfXTable(new OID(".1.3.6.1.2.1.31.1.1.1"), 1);

        @Getter
        private OID oid;
        @Getter
        private int column;

        Table(final OID oid, final int column) {
            this.oid = oid;
            this.column = column;
        }
    }

    private SnmpUtils() {
    }

    private static Map<Integer, String> walkTable(final Snmp snmp, final Target<?> target, final Table table) {
        final TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
        final List<TableEvent> tableEvents = tableUtils.getTable(target, new OID[]{table.getOid()}, null, null);

        final Map<Integer, String> snmpInterfaceMap = new TreeMap<>();

        for (final TableEvent tableEvent : tableEvents) {
            if (tableEvent.isError()) {
                LOG.warn("Error querying {} for target {}: {}", table.getOid(), target, tableEvent.getErrorMessage());
                return Collections.emptyMap();
            }

            int snmpIfIndex = tableEvent.getIndex().removeLast();
            int entry = tableEvent.getIndex().last();

            if (entry == table.getColumn()) {
                for (final VariableBinding vb : tableEvent.getColumns()) {
                    if (vb != null && vb.getVariable() != null) {
                        snmpInterfaceMap.put(snmpIfIndex, vb.getVariable().toString());
                    }
                }
            }
        }

        return snmpInterfaceMap;
    }

    public static Map<Integer, String> getSnmpInterfaceMap(final SnmpDefinition.SnmpEndpoint snmpEndpoint) throws IOException {
        final SnmpBuilder snmpBuilder = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getSnmpBuilder();
        final Snmp snmp = snmpBuilder.build();

        try {
            final Target<?> target = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getTarget(snmp, snmpBuilder, snmpEndpoint);
            Map<Integer, String> snmpInterfaceMap;
            // query ifXTable first, if not available fallback to ifTable
            snmpInterfaceMap = walkTable(snmp, target, IfXTable);
            if (snmpInterfaceMap == null || snmpInterfaceMap.isEmpty()) {
                snmpInterfaceMap = walkTable(snmp, target, IfTable);
            }

            return snmpInterfaceMap;
        } finally {
            snmp.close();
        }
    }
}