/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.auth;

import lombok.extern.slf4j.Slf4j;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Validates incoming MCP authorization tokens against configured SecretRef references.
 */
@Slf4j
@Service
public class McpAuthService {

    private final McpAuthProperties authProperties;
    private final SecretResolvers secretResolvers;

    public McpAuthService(final McpAuthProperties authProperties, final SecretResolvers secretResolvers) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties must not be null");
        this.secretResolvers = secretResolvers != null ? secretResolvers : SecretResolvers.defaults();
    }

    /**
     * Checks if the given token string matches any configured authorized SecretRef token.
     * If authentication is disabled or no tokens are configured, access is allowed.
     */
    public boolean authenticate(final String token) {
        if (!authProperties.isEnabled()) {
            return true;
        }

        if (authProperties.getTokens().isEmpty()) {
            log.warn("MCP authentication is enabled but no authorized tokens are configured in riptide.mcp.auth.tokens");
            return true;
        }

        if (token == null || token.isBlank()) {
            return false;
        }

        for (final SecretRef ref : authProperties.getTokens()) {
            try {
                final String resolvedSecret = secretResolvers.resolve(ref);
                if (resolvedSecret != null && resolvedSecret.equals(token)) {
                    return true;
                }
            } catch (final Exception e) {
                log.error("Failed to resolve MCP auth SecretRef [{}]: {}", ref, e.getMessage());
            }
        }

        return false;
    }
}
