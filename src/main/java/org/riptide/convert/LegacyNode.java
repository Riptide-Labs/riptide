/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.convert;

import java.util.Map;

/**
 * One legacy {@code riptide.nodes.<name>} entry, read into a shape the converter owns.
 *
 * <p>Deliberately not {@code NodeDefinition}. Story 3.2 deletes that class along with the
 * whole Spring binding path, and a converter that read the legacy tree through it would be
 * deleted with it — leaving operators on 0.8 with no way to upgrade. This record is the
 * seam that lets the legacy shape outlive the legacy code.</p>
 *
 * @param name the map key, preserved exactly as written: it becomes the enrichment entry
 *             key, which is what makes exporter names survive the upgrade
 * @param subnetAddress the address as written, host or prefix; not parsed here so that a
 *                      malformed one is reported against the node rather than the file
 * @param observationDomain {@code null} when the node pinned no domain
 * @param snmp {@code null} when the node was never polled
 * @param interfaces static interface pins by ifIndex, empty when there were none
 */
public record LegacyNode(String name,
                         String subnetAddress,
                         Long observationDomain,
                         LegacySnmp snmp,
                         Map<Integer, LegacyPin> interfaces) {

    /**
     * The legacy {@code snmp} block. Credential fields and cadence fields are mixed here as
     * they were in {@code SnmpDefinition}; the converter splits them, because 0.9 keeps
     * credentials in a credential set and timeout/retries on a polling profile.
     *
     * <p>Secret references are carried as the raw strings the operator wrote. The converter
     * never resolves them: it has no business reaching Vault, and a resolved value would be
     * a cleartext community written into a file.</p>
     */
    public record LegacySnmp(String version,
                             String community,
                             String securityName,
                             String authProtocol,
                             String authPassphrase,
                             String privProtocol,
                             String privPassphrase,
                             Integer timeout,
                             Integer retries,
                             Integer port) {

        /** The credential half, which is what deduplication groups on. */
        public LegacySnmp credentialsOnly() {
            return new LegacySnmp(this.version, this.community, this.securityName,
                    this.authProtocol, this.authPassphrase, this.privProtocol, this.privPassphrase,
                    null, null, null);
        }

        /** True when this is a community-based version, which is what the FR-9 width rule keys on. */
        public boolean cleartext() {
            return "v1".equals(this.version) || "v2c".equals(this.version);
        }
    }

    /** One {@code interfaces.<ifIndex>} pin. Any field may be absent. */
    public record LegacyPin(String name, String alias, Long highSpeed) {

        public boolean pinsNothing() {
            return this.name == null && this.alias == null && this.highSpeed == null;
        }
    }
}
