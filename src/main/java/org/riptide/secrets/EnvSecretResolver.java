/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Resolves {@code env://NAME} from environment variables.
 */
@Component
public class EnvSecretResolver implements SecretResolver {

    private final Function<String, String> environment;

    public EnvSecretResolver() {
        this(System::getenv);
    }

    EnvSecretResolver(final Function<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public String scheme() {
        return "env";
    }

    @Override
    public String resolve(final SecretRef ref) {
        final String value = this.environment.apply(ref.getValue());
        if (value == null) {
            throw new IllegalArgumentException("Environment variable '" + ref.getValue() + "' is not set (secret ref " + ref + ")");
        }
        return value;
    }
}
