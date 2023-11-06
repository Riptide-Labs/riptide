package org.riptide.snmp;

import lombok.extern.slf4j.Slf4j;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.riptide.snmp.SnmpTable.IfTable;
import static org.riptide.snmp.SnmpTable.IfXTable;

@Slf4j
public final class SnmpUtils {

    private SnmpUtils() {
    }

    private static Map<Integer, String> walkTable(final Snmp snmp, final Target<?> target, final SnmpTable table) {
        final TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
        final List<TableEvent> tableEvents = tableUtils.getTable(target, new OID[]{table.getOid()}, null, null);

        final Map<Integer, String> snmpInterfaceMap = new TreeMap<>();

        for (final TableEvent tableEvent : tableEvents) {
            if (tableEvent.isError()) {
                log.warn("Error querying {} for target {}: {}", table.getOid(), target, tableEvent.getErrorMessage());
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

    public static Map<Integer, String> getSnmpInterfaceMap(final SnmpEndpoint snmpEndpoint) throws IOException {
        final SnmpBuilder snmpBuilder = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getSnmpBuilder();
        try (Snmp snmp = snmpBuilder.build()) {
            final Target<?> target = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getTarget(snmp, snmpBuilder, snmpEndpoint);
            // query ifXTable first, if not available fallback to ifTable
            final var snmpInterfaceMap = walkTable(snmp, target, IfXTable);
            if (snmpInterfaceMap.isEmpty()) {
                return walkTable(snmp, target, IfTable);
            }
            return snmpInterfaceMap;
        }
    }
}