/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.snmp4j.fluent.TargetBuilder;

import java.util.Locale;

/**
 * A named SNMP authentication definition, configured once under
 * {@code riptide.snmp.credentials.<name>} and referenced by agent ranges. Shape
 * validation runs at bind time in {@link SnmpProfilesConfig}; the endpoint factory
 * on the snmp side turns a set plus an address into an SNMP target with secrets
 * resolved lazily at walk time.
 */
@Data
public class CredentialSet {

    private CredentialVersion version;

    private SecretRef community;

    private String securityName;

    private TargetBuilder.AuthProtocol authProtocol;

    private SecretRef authPassphrase;

    private TargetBuilder.PrivProtocol privProtocol;

    private SecretRef privPassphrase;

    /**
     * The shape contract, callable by any producer (bind-time config today, other
     * inventory sources later): version-appropriate fields present, foreign fields
     * rejected, USM pairs complete. Errors name the set.
     */
    public void validate(final String name) {
        if (this.version == null) {
            throw new IllegalStateException(
                    "Credential set '%s' has no version: one of v1, v2c or v3 is required.".formatted(name));
        }
        switch (this.version) {
            case V1, V2C -> validateCommunity(name);
            case V3 -> validateUsm(name);
            default -> throw new IllegalStateException(
                    "Credential set '%s' has unhandled version %s.".formatted(name, this.version));
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
}
