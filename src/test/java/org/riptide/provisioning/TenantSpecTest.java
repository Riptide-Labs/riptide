/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TenantSpec} validation is a security boundary: the tool runs as admin and interpolates the
 * tenant/org into ClickHouse identifiers and literals, so an unsafe name must be rejected at
 * construction rather than reaching the SQL.
 */
class TenantSpecTest {

    @Test
    void acceptsSafeNames() {
        assertThatCode(() -> new TenantSpec("acme", "acme-eu", "riptide", "w", "r", 1L))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeTenant() {
        for (final String bad : new String[] {"a'b", "a`b", "a b", "a;b", "", "acme\""}) {
            assertThatThrownBy(() -> new TenantSpec(bad, "acme-eu", "riptide", "w", "r", 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenant");
        }
    }

    @Test
    void rejectsUnsafeOrganisationAndDatabase() {
        assertThatThrownBy(() -> new TenantSpec("acme", "bad org", "riptide", "w", "r", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("organisation");
        assertThatThrownBy(() -> new TenantSpec("acme", "acme-eu", "bad db", "w", "r", 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("database");
    }

    @Test
    void rejectsBlankSecretsAndNonPositiveQuota() {
        assertThatThrownBy(() -> new TenantSpec("acme", "acme-eu", "riptide", " ", "r", 1L))
                .hasMessageContaining("writerSecret");
        assertThatThrownBy(() -> new TenantSpec("acme", "acme-eu", "riptide", "w", "", 1L))
                .hasMessageContaining("readerSecret");
        assertThatThrownBy(() -> new TenantSpec("acme", "acme-eu", "riptide", "w", "r", 0L))
                .hasMessageContaining("quotaBytes");
    }
}
