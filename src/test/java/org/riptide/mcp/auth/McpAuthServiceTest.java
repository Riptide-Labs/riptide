/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.auth;

import org.junit.jupiter.api.Test;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class McpAuthServiceTest {

    @Test
    public void allowsAccessWhenAuthDisabled() {
        final McpAuthProperties properties = new McpAuthProperties();
        properties.setEnabled(false);

        final McpAuthService authService = new McpAuthService(properties, SecretResolvers.defaults());
        assertThat(authService.authenticate(null)).isTrue();
        assertThat(authService.authenticate("any_token")).isTrue();
    }

    @Test
    public void validatesTokenMatchingPlainSecretRef() {
        final McpAuthProperties properties = new McpAuthProperties();
        properties.setEnabled(true);
        properties.setTokens(List.of(new SecretRef("secret_token_123")));

        final McpAuthService authService = new McpAuthService(properties, SecretResolvers.defaults());
        assertThat(authService.authenticate("secret_token_123")).isTrue();
        assertThat(authService.authenticate("invalid_token")).isFalse();
        assertThat(authService.authenticate(null)).isFalse();
    }
}
