/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.provisioning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantProvisioner#redact} must never leak a resolved password into an error message,
 * including the tricky cases a naive {@code '.*?'} misses: an escaped quote inside the literal and
 * a newline in the password.
 */
class TenantProvisionerTest {

    @Test
    void redactsPlainPassword() {
        final String sql = "CREATE USER `writer_acme` IDENTIFIED WITH sha256_password BY 's3cr3t' SETTINGS x = 1";
        assertThat(TenantProvisioner.redact(sql))
                .isEqualTo("CREATE USER `writer_acme` IDENTIFIED WITH sha256_password BY '***' SETTINGS x = 1")
                .doesNotContain("s3cr3t");
    }

    @Test
    void redactsPasswordContainingEscapedQuote() {
        // literal("a'b") -> 'a\'b' ; the escaped quote must not end the redaction early.
        final String sql = "ALTER USER `writer_acme` IDENTIFIED WITH sha256_password BY 'a\\'b'";
        assertThat(TenantProvisioner.redact(sql))
                .isEqualTo("ALTER USER `writer_acme` IDENTIFIED WITH sha256_password BY '***'")
                .doesNotContain("a\\'b").doesNotContain("b'");
    }

    @Test
    void redactsPasswordContainingNewline() {
        final String sql = "ALTER USER `writer_acme` IDENTIFIED WITH sha256_password BY 'pre\npost'";
        assertThat(TenantProvisioner.redact(sql))
                .isEqualTo("ALTER USER `writer_acme` IDENTIFIED WITH sha256_password BY '***'")
                .doesNotContain("post");
    }
}
