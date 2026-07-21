/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riptide.clickhouse")
public final class ClickhouseConfig {
        private String endpoint = "http://localhost:8123";

        /**
         * ClickHouse credentials as {@link SecretRef}s: a bare literal binds through the plain
         * fallback (existing configs keep working), while a {@code scheme://…} reference is
         * resolved from a secret store at repository construction. Left unset — or explicitly
         * blank (e.g. {@code riptide.clickhouse.password=}) — binds null for the default user /
         * empty password. Per-tenant writer credentials are sourced this way, with no plaintext in
         * configuration.
         */
        private SecretRef username;
        private SecretRef password;

        private String database = "riptide";

        /**
         * When {@code true} (default), riptide ensures the ClickHouse schema idempotently at
         * startup. When {@code false}, riptide creates nothing and instead validates that an
         * admin-provisioned {@code flows} table is present, failing fast if it is not — the
         * multi-tenant / provisioned mode.
         */
        private boolean manageSchema = true;

        /**
         * Server-side insert coalescing ({@code async_insert}). The pipeline inserts once per
         * received packet, and each insert also feeds the rollup materialized views — without
         * coalescing, that many small inserts collapse ingestion throughput on modest hardware
         * (measured 206 → 56 inserts/s with the four rollups on two cores). With coalescing the
         * server buffers and merges them (measured 607 inserts/s), at the price of asynchronous
         * error reporting: the insert is acknowledged when buffered, so a row the server later
         * rejects — notably a mis-tenanted row failing the multi-tenant CHECK barrier — is dropped
         * without the collector seeing an error.
         *
         * <p>Unset (default) follows {@link #manageSchema}: on in manage mode (single-tenant,
         * where the barrier does not exist and flow transport is lossy UDP anyway), off in
         * provisioned mode (where the barrier's synchronous rejection is part of the isolation
         * contract). Set explicitly to override either way.
         */
        private Boolean asyncInserts;

        /** The effective setting: the explicit value if set, otherwise on exactly in manage mode. */
        public boolean isAsyncInserts() {
                return this.asyncInserts != null ? this.asyncInserts : this.manageSchema;
        }
}
