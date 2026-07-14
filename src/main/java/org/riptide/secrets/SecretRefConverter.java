/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Binds configuration Strings to {@link SecretRef}. A blank value maps to {@code null} (an
 * unset credential) rather than failing — {@link SecretRef}'s constructor rejects blank, but an
 * explicitly-empty property (e.g. {@code riptide.clickhouse.password=}) must mean "no secret",
 * the same as leaving it unset. A non-blank value binds through {@link SecretRef}'s parser
 * (plain literal or {@code scheme://value#key}).
 */
@Component
@ConfigurationPropertiesBinding
public class SecretRefConverter implements Converter<String, SecretRef> {

    @Override
    @Nullable
    public SecretRef convert(final String source) {
        return source == null || source.isBlank() ? null : new SecretRef(source);
    }
}
