/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks Spring wiring of the conditional vault resolver: the bean must be constructible
 * from properties (constructor selection), and a resolve against an unreachable server
 * must keep the SPI's IllegalArgumentException contract so enrichment degrades instead
 * of failing the pipeline. No Vault server is involved.
 */
@SpringBootTest(properties = {
        "riptide.secrets.vault.uri=http://127.0.0.1:1",
        "riptide.secrets.vault.token=dummy-token"
})
public class VaultSecretResolverSpringTest {

    @Autowired
    SecretResolvers secretResolvers;

    @Autowired
    VaultSecretResolver vaultSecretResolver;

    @Test
    public void vaultResolverIsRegisteredWhenUriIsConfigured() {
        assertThat(this.vaultSecretResolver).isNotNull();
    }

    @Test
    public void unreachableVaultDegradesToIllegalArgumentException() {
        assertThatThrownBy(() -> this.secretResolvers.resolve(SecretRef.of("vault://secret/snmp/core#community")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot resolve secret ref");
    }
}
