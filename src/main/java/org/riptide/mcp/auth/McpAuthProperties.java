/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.auth;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Authentication configuration properties for Riptide's MCP server.
 */
@Data
@ConfigurationProperties(prefix = "riptide.mcp.auth")
public class McpAuthProperties {

    /**
     * Whether token authentication is required for MCP transports.
     */
    private boolean enabled = true;

    /**
     * List of SecretRef references for authorized MCP access tokens.
     */
    private List<SecretRef> tokens = new ArrayList<>();
}
