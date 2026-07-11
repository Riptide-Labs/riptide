/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import org.junit.jupiter.api.Test;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.VaultSecretResolver;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
public class VaultSecretResolverIT {

    private static final String ROOT_TOKEN = "riptide-test-root";

    @Container
    private static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:1.20")
            .withVaultToken(ROOT_TOKEN)
            // dev mode mounts a KV v2 engine at secret/
            .withInitCommand("kv put secret/snmp/core-router community=v4ult-c0mmunity auth=v4ult-auth");

    private VaultSecretResolver resolver() {
        final VaultTemplate template = new VaultTemplate(
                VaultEndpoint.from(URI.create(VAULT.getHttpHostAddress())),
                new TokenAuthentication(ROOT_TOKEN));
        return new VaultSecretResolver(template);
    }

    @Test
    public void resolvesKvV2Secrets() {
        final VaultSecretResolver resolver = resolver();

        assertThat(resolver.resolve(SecretRef.of("vault://secret/snmp/core-router#community"))).isEqualTo("v4ult-c0mmunity");
        assertThat(resolver.resolve(SecretRef.of("vault://secret/snmp/core-router#auth"))).isEqualTo("v4ult-auth");
    }

    @Test
    public void missingKeyOrSecretThrows() {
        final VaultSecretResolver resolver = resolver();

        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("vault://secret/snmp/core-router#nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("vault://secret/snmp/unknown-device#community")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void refWithoutKeyOrMountThrows() {
        final VaultSecretResolver resolver = resolver();

        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("vault://secret/snmp/core-router")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#key");
        assertThatThrownBy(() -> resolver.resolve(SecretRef.of("vault://secretonly#k")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vault://<mount>/<path>#<key>");
    }
}
