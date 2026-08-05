/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.auth;

import lombok.extern.slf4j.Slf4j;
import org.riptide.mcp.config.ConditionalOnMcpEnabled;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Validates incoming MCP authorization tokens against configured SecretRef references.
 * Resolves secret tokens at initialization and performs constant-time string comparisons.
 */
@Slf4j
@ConditionalOnMcpEnabled
@Service
public class McpAuthService {

    private final McpAuthProperties authProperties;
    private final SecretResolvers secretResolvers;
    private final List<String> resolvedTokens = new CopyOnWriteArrayList<>();

    public McpAuthService(final McpAuthProperties authProperties, final SecretResolvers secretResolvers) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties must not be null");
        this.secretResolvers = secretResolvers != null ? secretResolvers : SecretResolvers.defaults();
        initTokens();
    }

    private synchronized void initTokens() {
        resolvedTokens.clear();
        for (final SecretRef ref : authProperties.getTokens()) {
            try {
                final String resolved = secretResolvers.resolve(ref);
                if (resolved != null && !resolved.isBlank()) {
                    resolvedTokens.add(resolved);
                }
            } catch (final Exception e) {
                log.error("Failed to resolve MCP auth secret reference: {}", e.getMessage());
            }
        }
    }

    /**
     * Checks if authentication is required.
     */
    public boolean isAuthRequired() {
        return authProperties.isEnabled();
    }

    /**
     * Checks if the given token string matches any configured authorized SecretRef token.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public boolean authenticate(final String token) {
        if (!authProperties.isEnabled()) {
            return true;
        }

        if (authProperties.getTokens().isEmpty()) {
            log.error("MCP authentication is enabled but no authorized tokens are configured; failing closed.");
            return false;
        }

        if (token == null || token.isBlank()) {
            return false;
        }

        final byte[] inputBytes = token.getBytes(StandardCharsets.UTF_8);

        for (final String validToken : resolvedTokens) {
            final byte[] validBytes = validToken.getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(inputBytes, validBytes)) {
                return true;
            }
        }

        return false;
    }
}
