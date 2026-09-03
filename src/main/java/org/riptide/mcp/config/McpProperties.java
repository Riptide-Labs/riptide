/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.config;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Main configuration properties for the native Model Context Protocol (MCP) server component.
 */
@Data
@ConfigurationProperties(prefix = "riptide.mcp")
public class McpProperties {

    /**
     * Whether the MCP server component is enabled.
     */
    private boolean enabled = false;

    /**
     * Transport mechanism for MCP communication ("stdio" or "sse").
     */
    private String transport = "stdio";

    /**
     * Port for MCP SSE transport HTTP server. Deliberately not 8080: that is the management
     * server's default ({@code riptide.management.port}), and both bind in the same process.
     */
    private int ssePort = 8081;

    /**
     * Address the SSE transport binds to. Loopback by default — the endpoint speaks unauthenticated
     * JSON-RPC unless {@code riptide.mcp.auth.enabled} is set, so exposing it needs a deliberate act.
     */
    private String bindAddress = "127.0.0.1";

    /**
     * Maximum number of concurrent SSE stream sessions. Each session holds a socket and a thread
     * for its lifetime, so this bounds what a reconnect loop or a scanner can pin.
     */
    private int maxSseSessions = 64;

    /**
     * How often an idle SSE session writes a keep-alive comment. This is also how a client that
     * vanished without closing the connection is noticed: the write fails and the session is
     * released, so it doubles as the upper bound on how long a dead session lingers.
     */
    private Duration sseKeepAliveInterval = Duration.ofSeconds(15);

    /**
     * Maximum execution time in seconds for ClickHouse MCP queries.
     */
    private int queryTimeoutSeconds = 5;

    /**
     * Maximum number of rows returned by MCP flow queries.
     */
    private int maxResultRows = 50;

    /**
     * Credentials the MCP read path uses against ClickHouse.
     */
    private Clickhouse clickhouse = new Clickhouse();

    /**
     * The read-only ClickHouse identity for MCP queries, kept separate from the ingest credentials
     * in {@code riptide.clickhouse}. Provisioned deployments must point this at the tenant reader
     * ({@code bi_<tenant>@<database>}): it holds {@code flow_reader@<database>}, which already carries SELECT on
     * {@code flows} and every rollup plus the {@code readonly = 2} / {@code allow_ddl = 0}
     * hardening, and is already named on every row policy. Pointing MCP at the ingest writer
     * instead would mean widening the writer's grants and its row-policy membership to cover the
     * rollups — handing the credential that only needs INSERT a tenant-wide read surface.
     *
     * <p>Unset falls back to {@code riptide.clickhouse.username}/{@code password}, which is correct
     * for single-tenant manage mode where that user reads and writes anyway.
     */
    @Data
    public static class Clickhouse {
        private SecretRef username;
        private SecretRef password;
    }
}
