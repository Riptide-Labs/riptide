/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a bean to deployments that actually run the MCP server
 * ({@code riptide.mcp.enabled=true}).
 *
 * <p>The MCP components are not free to construct: the auth service resolves every configured token
 * through the secret resolvers (which can mean a Vault round trip or a SOPS subprocess) and the
 * skill registry scans and parses the classpath. Both happen in a constructor, so without this a
 * collector that never speaks MCP still pays for them on every start, and a Vault outage could fail
 * a startup that has nothing to do with MCP.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "riptide.mcp.enabled", havingValue = "true")
public @interface ConditionalOnMcpEnabled {
}
