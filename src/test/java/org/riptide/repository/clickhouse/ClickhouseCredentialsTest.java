/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.secrets.PlainSecretResolver;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolver;
import org.riptide.secrets.SecretResolvers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level proof that the ClickHouse credential {@link SecretRef}s resolve the way the
 * repository relies on: a bare literal maps to itself (plain fallback, existing configs keep
 * working), a {@code scheme://} reference resolves through {@link SecretResolvers}, and an
 * unresolvable reference fails repository construction (a database credential that cannot resolve
 * is fatal). Building the client here does not open a connection, so no server is needed.
 */
class ClickhouseCredentialsTest {

    @Test
    void plainLiteralResolvesToItself() {
        final var config = baseConfig();
        config.setUsername(SecretRef.of("writer_acme"));
        config.setPassword(SecretRef.of("s3cr3t"));

        final var resolvers = SecretResolvers.defaults();
        assertThat(resolvers.resolve(config.getUsername())).isEqualTo("writer_acme");
        assertThat(resolvers.resolve(config.getPassword())).isEqualTo("s3cr3t");

        assertThatCode(() -> new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, resolvers))
                .doesNotThrowAnyException();
    }

    @Test
    void envRefResolvesViaResolvers() {
        final var config = baseConfig();
        config.setPassword(SecretRef.of("env://RIPTIDE_CH_PASSWORD"));

        final var resolvers = new SecretResolvers(List.of(
                new PlainSecretResolver(),
                new StubEnvResolver(Map.of("RIPTIDE_CH_PASSWORD", "fr0m-env"))));

        assertThat(resolvers.resolve(config.getPassword())).isEqualTo("fr0m-env");
        assertThatCode(() -> new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, resolvers))
                .doesNotThrowAnyException();
    }

    @Test
    void unresolvableRefFailsConstruction() {
        final var config = baseConfig();
        config.setPassword(SecretRef.of("env://MISSING_CH_PASSWORD"));

        final var resolvers = new SecretResolvers(List.of(
                new PlainSecretResolver(),
                new StubEnvResolver(Map.of())));

        assertThatThrownBy(() -> new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, resolvers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING_CH_PASSWORD");
    }

    private static ClickhouseConfig baseConfig() {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://localhost:8123");
        config.setUsername(SecretRef.of("default"));
        return config;
    }

    /** A test-only {@code env://} resolver backed by an in-memory map. */
    private record StubEnvResolver(Map<String, String> values) implements SecretResolver {
        @Override
        public String scheme() {
            return "env";
        }

        @Override
        public String resolve(final SecretRef ref) {
            final String value = this.values.get(ref.getValue());
            if (value == null) {
                throw new IllegalArgumentException("environment variable '" + ref.getValue() + "' is not set");
            }
            return value;
        }
    }
}
