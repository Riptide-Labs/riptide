/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.secrets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.net.URI;

/**
 * Resolves {@code vault://<mount>/<path>#<key>} from a HashiCorp Vault KV v2 secrets engine,
 * e.g. {@code vault://secret/snmp/core-router#community}.
 *
 * <p>Registered only when {@code riptide.secrets.vault.uri} is configured. Authentication is
 * token-based ({@code riptide.secrets.vault.token}); with a Vault Agent sidecar, point the
 * token at the agent's sink (e.g. {@code ${VAULT_TOKEN}}).</p>
 */
@Component
@ConditionalOnProperty("riptide.secrets.vault.uri")
public class VaultSecretResolver implements SecretResolver {

    private final VaultOperations vault;

    @Autowired
    public VaultSecretResolver(@Value("${riptide.secrets.vault.uri}") final URI uri,
                               @Value("${riptide.secrets.vault.token:}") final String token) {
        this(createTemplate(uri, token));
    }

    public VaultSecretResolver(final VaultOperations vault) {
        this.vault = vault;
    }

    private static VaultTemplate createTemplate(final URI uri, final String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("riptide.secrets.vault.uri is set but riptide.secrets.vault.token is missing");
        }
        return new VaultTemplate(VaultEndpoint.from(uri), new TokenAuthentication(token));
    }

    @Override
    public String scheme() {
        return "vault";
    }

    @Override
    public String resolve(final SecretRef ref) {
        if (ref.getKey() == null) {
            throw new IllegalArgumentException("Secret ref " + ref + " is missing the #key selecting a field of the secret");
        }
        final int mountEnd = ref.getValue().indexOf('/');
        if (mountEnd <= 0 || mountEnd == ref.getValue().length() - 1) {
            throw new IllegalArgumentException("Secret ref " + ref + " must have the form vault://<mount>/<path>#<key>");
        }
        final String mount = ref.getValue().substring(0, mountEnd);
        final String path = ref.getValue().substring(mountEnd + 1);

        final VaultResponse response;
        try {
            response = this.vault.opsForKeyValue(mount, KeyValueBackend.KV_2).get(path);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // Vault client errors (server unreachable, auth failure, …) must keep the SPI
            // contract of IllegalArgumentException so enrichment degrades instead of failing
            throw new IllegalArgumentException("Cannot resolve secret ref " + ref + " from Vault: " + e.getMessage(), e);
        }
        if (response == null || response.getData() == null) {
            throw new IllegalArgumentException("No secret found for ref " + ref);
        }
        final Object value = response.getData().get(ref.getKey());
        if (value == null) {
            throw new IllegalArgumentException("Key '" + ref.getKey() + "' not found for secret ref " + ref);
        }
        return value.toString();
    }
}
