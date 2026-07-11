/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dispatches a {@link SecretRef} to the {@link SecretResolver} registered for its scheme.
 */
@Service
public class SecretResolvers {

    private final Map<String, SecretResolver> resolvers;

    public SecretResolvers(final List<SecretResolver> resolvers) {
        this.resolvers = resolvers.stream().collect(Collectors.toMap(SecretResolver::scheme, Function.identity()));
    }

    /**
     * Null-safe: a {@code null} reference (optional credential) resolves to {@code null}.
     *
     * @throws IllegalArgumentException on an unknown scheme or a failing resolver
     */
    public String resolve(final SecretRef ref) {
        if (ref == null) {
            return null;
        }
        final SecretResolver resolver = this.resolvers.get(ref.getScheme());
        if (resolver == null) {
            throw new IllegalArgumentException("No secret resolver for scheme '" + ref.getScheme() + "' (secret ref " + ref + ")");
        }
        return resolver.resolve(ref);
    }

    /**
     * Registry with all built-in resolvers — for use outside a Spring context (tests, tools).
     */
    public static SecretResolvers defaults() {
        return new SecretResolvers(List.of(new PlainSecretResolver(), new EnvSecretResolver(), new FileSecretResolver(List.of())));
    }
}
