/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

/**
 * Resolves a {@link SecretRef} of a single scheme to its secret value. Implementations are
 * discovered as Spring beans and dispatched by {@link SecretResolvers}.
 */
public interface SecretResolver {

    String scheme();

    /**
     * @throws IllegalArgumentException if the reference cannot be resolved
     */
    String resolve(SecretRef ref);
}
