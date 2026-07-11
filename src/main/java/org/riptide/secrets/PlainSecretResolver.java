/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.springframework.stereotype.Component;

/**
 * Literal fallback for values without a scheme. Intended for migration and test fixtures;
 * production config should use {@code env://} or {@code file://} references.
 */
@Component
public class PlainSecretResolver implements SecretResolver {

    @Override
    public String scheme() {
        return SecretRef.SCHEME_PLAIN;
    }

    @Override
    public String resolve(final SecretRef ref) {
        return ref.getValue();
    }
}
