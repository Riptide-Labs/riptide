/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.snmp4j.CommunityTarget;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Ground-truth ifName lookup for the full-mode e2e test: walks a device's
 * ifXTable directly with plain snmp4j, independent of riptide's own SNMP
 * service code (which is what the test is verifying).
 */
public final class SnmpIfNameWalker {

    private static final OID IF_NAME = new OID("1.3.6.1.2.1.31.1.1.1.1");

    private SnmpIfNameWalker() {
    }

    /** Walks ifXTable ifName (v2c/public) and returns ifIndex -> ifName. */
    public static Map<Integer, String> walkIfNames(final String address) throws IOException {
        try (var snmp = new Snmp(new DefaultUdpTransportMapping())) {
            snmp.listen();

            final var target = new CommunityTarget<UdpAddress>();
            target.setCommunity(new OctetString("public"));
            target.setAddress(new UdpAddress(InetAddress.getByName(address), 161));
            target.setVersion(SnmpConstants.version2c);
            target.setTimeout(2000);
            target.setRetries(2);

            final var tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
            final var names = new HashMap<Integer, String>();
            for (final var row : tableUtils.getTable(target, new OID[]{IF_NAME}, null, null)) {
                if (row.isError() || row.getColumns() == null || row.getColumns()[0] == null) {
                    continue;
                }
                names.put(row.getIndex().get(0), row.getColumns()[0].getVariable().toString());
            }
            return names;
        }
    }
}
