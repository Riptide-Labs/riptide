/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;

import java.util.Locale;

/**
 * A named SNMP authentication definition, configured once under
 * {@code riptide.snmp.credentials.<name>} and referenced by agent ranges. Shape
 * validation runs at bind time in {@link SnmpProfilesConfig}; the endpoint factory
 * on the snmp side turns a set plus an address into an SNMP target with secrets
 * resolved lazily at walk time.
 *
 * <p>A record rather than a bean, so that bind-time validation is an invariant: one
 * set is shared by every range naming it and by every snapshot a reload produces, and
 * while it was mutable a single setter could have retuned a validated pairing (a v3
 * set flipped to v2c, say) with nothing revalidating it. Constructor binding keeps the
 * kebab-case property names unchanged for operators.</p>
 *
 * @param version the SNMP version this set speaks
 * @param community the community string, v1/v2c only
 * @param securityName the USM security name, v3 only
 * @param authProtocol the USM authentication protocol, v3 only
 * @param authPassphrase the USM authentication passphrase, v3 only
 * @param privProtocol the USM privacy protocol, v3 only
 * @param privPassphrase the USM privacy passphrase, v3 only
 */
public record CredentialSet(CredentialVersion version,
                            SecretRef community,
                            String securityName,
                            TargetBuilder.AuthProtocol authProtocol,
                            SecretRef authPassphrase,
                            TargetBuilder.PrivProtocol privProtocol,
                            SecretRef privPassphrase) {

    /**
     * The shape contract, callable by any producer (bind-time config today, other
     * inventory sources later): version-appropriate fields present, foreign fields
     * rejected, USM pairs complete. Errors name the set.
     *
     * <p>Deliberately not folded into the canonical constructor: the compact form has
     * no access to the map key, so the failure would lose the set name from the root
     * cause that operators read.</p>
     */
    public void validate(final String name) {
        if (this.version == null) {
            throw new IllegalStateException(
                    "Credential set '%s' has no version: one of v1, v2c or v3 is required.".formatted(name));
        }
        // a switch expression with no default: exhaustiveness is checked by the compiler,
        // so a new version is a build error rather than a silently unvalidated shape. The
        // equivalent multi-label statement reads the same to a human and does not
        // (scanners flag it as missing a case, and nothing enforces the arms)
        final boolean community = switch (this.version) {
            case V1, V2C -> true;
            case V3 -> false;
        };
        if (community) {
            validateCommunity(name);
        } else {
            validateUsm(name);
        }
    }

    private void validateCommunity(final String name) {
        final String label = this.version.name().toLowerCase(Locale.ROOT);
        if (this.community == null) {
            throw new IllegalStateException("Credential set '%s' (%s) has no community.".formatted(name, label));
        }
        // foreign fields are rejected, not ignored: leftover USM fields on a
        // community set would read as authenticated while every walk goes out
        // plaintext, the same silent-downgrade class the USM pairing rules close
        if (this.securityName != null || this.authProtocol != null || this.authPassphrase != null
                || this.privProtocol != null || this.privPassphrase != null) {
            throw new IllegalStateException(
                    "Credential set '%s' (%s) carries v3 fields; remove them or set version v3."
                            .formatted(name, label));
        }
    }

    private void validateUsm(final String name) {
        if (this.securityName == null || this.securityName.isBlank()) {
            throw new IllegalStateException("Credential set '%s' (v3) has no security-name.".formatted(name));
        }
        if (this.community != null) {
            throw new IllegalStateException(
                    "Credential set '%s' (v3) carries a community; remove it or set version v1/v2c.".formatted(name));
        }
        if ((this.authProtocol == null) != (this.authPassphrase == null)) {
            throw new IllegalStateException(
                    "Credential set '%s' (v3) pairs auth-protocol and auth-passphrase incompletely: set both or neither."
                            .formatted(name));
        }
        if ((this.privProtocol == null) != (this.privPassphrase == null)) {
            throw new IllegalStateException(
                    "Credential set '%s' (v3) pairs priv-protocol and priv-passphrase incompletely: set both or neither."
                            .formatted(name));
        }
        if (this.privProtocol != null && this.authProtocol == null) {
            throw new IllegalStateException(
                    "Credential set '%s' (v3) sets priv without auth: USM has no priv-only security level."
                            .formatted(name));
        }
    }

    /** A v1/v2c set: version and community, the only fields those versions carry. */
    public static CredentialSet community(final CredentialVersion version, final SecretRef community) {
        return new CredentialSet(version, community, null, null, null, null, null);
    }

    /** A v3 set with no auth or priv, the noAuthNoPriv shape. */
    public static CredentialSet usm(final String securityName) {
        return new CredentialSet(CredentialVersion.V3, null, securityName, null, null, null, null);
    }
}
