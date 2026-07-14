/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import java.util.regex.Pattern;

/**
 * A validated request to onboard a {@code (tenant, organisation)}. The tenant and organisation are
 * constrained to a safe charset so they cannot break out of the generated identifiers or literals
 * (this tool runs with admin credentials — an unvalidated tenant name would be an injection vector).
 *
 * @param tenant         tenant id (hard isolation); becomes part of the {@code writer_}/{@code bi_}
 *                       user names and the row-policy name
 * @param organisation   organisation id (hard isolation)
 * @param database       ClickHouse database holding the {@code flows} table
 * @param writerSecret   secret reference resolving to the writer user's password
 * @param readerSecret   secret reference resolving to the reader (BI) user's password
 * @param quotaBytes     per-writer hourly ingest ceiling for the shared keyed quota
 */
public record TenantSpec(String tenant,
                         String organisation,
                         String database,
                         String writerSecret,
                         String readerSecret,
                         long quotaBytes) {

    /** Tenant/org/database: letters, digits, underscore, hyphen — no quotes, backticks, or spaces. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    public TenantSpec {
        requireSafe("tenant", tenant);
        requireSafe("organisation", organisation);
        requireSafe("database", database);
        if (writerSecret == null || writerSecret.isBlank()) {
            throw new IllegalArgumentException("writerSecret must not be blank");
        }
        if (readerSecret == null || readerSecret.isBlank()) {
            throw new IllegalArgumentException("readerSecret must not be blank");
        }
        if (quotaBytes <= 0) {
            throw new IllegalArgumentException("quotaBytes must be positive, was " + quotaBytes);
        }
    }

    /** The tenant/org/database injection boundary — the single charset check for the package. */
    static void requireSafe(final String field, final String value) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must match [A-Za-z0-9_-]+ (letters, digits, underscore, hyphen), was: " + value);
        }
    }
}
