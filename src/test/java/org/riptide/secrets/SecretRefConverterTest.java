/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The converter binds configuration Strings to {@link SecretRef}. The load-bearing case is that a
 * blank value maps to {@code null} rather than throwing: an explicitly-empty property such as
 * {@code riptide.clickhouse.password=} must mean "no secret" (default user / empty password), the
 * same as leaving it unset — {@link SecretRef}'s constructor rejects blank, so without this
 * converter that property would fail startup.
 */
class SecretRefConverterTest {

    private final SecretRefConverter converter = new SecretRefConverter();

    @Test
    void blankMapsToNull() {
        assertThat(this.converter.convert("")).isNull();
        assertThat(this.converter.convert("   ")).isNull();
    }

    @Test
    void plainLiteralBindsThroughSecretRef() {
        assertThat(this.converter.convert("s3cr3t")).isEqualTo(SecretRef.of("s3cr3t"));
    }

    @Test
    void schemeReferenceBindsThroughSecretRef() {
        final SecretRef ref = this.converter.convert("env://RIPTIDE_CH_PASSWORD");
        assertThat(ref.getScheme()).isEqualTo("env");
        assertThat(ref.getValue()).isEqualTo("RIPTIDE_CH_PASSWORD");
    }
}
