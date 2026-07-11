/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * A reference to a secret, never the secret itself. Parsed from a URI-style string:
 *
 * <ul>
 *   <li>{@code env://RIPTIDE_SNMP_COMMUNITY} — environment variable</li>
 *   <li>{@code file:///run/secrets/community} — file content (trimmed)</li>
 *   <li>{@code file:///etc/riptide/secrets.properties#snmp.community} — key inside a properties file</li>
 *   <li>any value without {@code ://} — literal fallback ({@code plain}) for migration and tests</li>
 * </ul>
 *
 * The single-String constructor lets Spring bind configuration properties directly.
 * {@link #toString()} never exposes a literal secret.
 */
@Getter
@EqualsAndHashCode
public final class SecretRef {

    public static final String SCHEME_PLAIN = "plain";

    private final String scheme;
    private final String value;
    private final String key;

    public SecretRef(final String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Secret reference must not be blank");
        }

        final int schemeEnd = ref.indexOf("://");
        if (schemeEnd <= 0) {
            this.scheme = SCHEME_PLAIN;
            this.value = ref;
            this.key = null;
            return;
        }

        this.scheme = ref.substring(0, schemeEnd);
        final String rest = ref.substring(schemeEnd + 3);
        final int fragment = rest.indexOf('#');
        if (fragment >= 0) {
            this.value = rest.substring(0, fragment);
            this.key = rest.substring(fragment + 1);
        } else {
            this.value = rest;
            this.key = null;
        }

        if (this.value.isBlank()) {
            throw new IllegalArgumentException("Secret reference '" + this + "' has no value part");
        }
    }

    /**
     * Null-safe factory: {@code null} stays {@code null} (optional credentials such as
     * SNMPv3 auth/priv passphrases).
     */
    public static SecretRef of(final String ref) {
        return ref == null ? null : new SecretRef(ref);
    }

    @Override
    public String toString() {
        if (SCHEME_PLAIN.equals(this.scheme)) {
            return "plain://***";
        }
        return this.scheme + "://" + this.value + (this.key != null ? "#" + this.key : "");
    }
}
