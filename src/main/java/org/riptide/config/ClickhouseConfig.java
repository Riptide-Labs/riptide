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
}
