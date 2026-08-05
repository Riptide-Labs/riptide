/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.ClickhouseConfig;
import org.riptide.mcp.config.ConditionalOnMcpEnabled;
import org.riptide.mcp.config.McpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service orchestrating ClickHouse queries for MCP tools with automatic rollup routing and execution timeouts.
 */
@Slf4j
@ConditionalOnMcpEnabled
@Service
public class RiptideMcpService {

    private final Client clickhouseClient;
    private final ClickhouseConfig clickhouseConfig;
    private final McpProperties mcpProperties;
    private final ObjectMapper objectMapper;

    public RiptideMcpService(@Autowired(required = false) final Client clickhouseClient,
                             final ClickhouseConfig clickhouseConfig,
                             final McpProperties mcpProperties,
                             final ObjectMapper objectMapper) {
        this.clickhouseClient = clickhouseClient;
        this.clickhouseConfig = Objects.requireNonNull(clickhouseConfig, "clickhouseConfig must not be null");
        this.mcpProperties = Objects.requireNonNull(mcpProperties, "mcpProperties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Executes a read-only SQL query against ClickHouse and returns formatted generic record maps.
     */
    public List<Map<String, Object>> executeQuery(final String sqlQuery) {
        final List<Map<String, Object>> results = new ArrayList<>();

        if (clickhouseClient == null) {
            log.warn("ClickHouse client is not available for MCP query execution.");
            final Map<String, Object> errorMap = new LinkedHashMap<>();
            errorMap.put("error", "ClickHouse client is unavailable.");
            results.add(errorMap);
            return results;
        }

        final String cleanSql = sqlQuery != null ? sqlQuery.trim().replaceAll(";+$", "") : "";
        final String formattedQuery = cleanSql + " FORMAT JSONEachRow SETTINGS max_execution_time = "
                + mcpProperties.getQueryTimeoutSeconds() + ", readonly = 1";

        log.debug("Executing MCP ClickHouse Query: [{}]", formattedQuery);

        try {
            final CompletableFuture<QueryResponse> future = clickhouseClient.query(formattedQuery);
            final QueryResponse response = future.get(mcpProperties.getQueryTimeoutSeconds(), TimeUnit.SECONDS);

            try (var reader = new BufferedReader(new InputStreamReader(response.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final Map<String, Object> row = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() { });
                    results.add(row);
                    if (results.size() >= mcpProperties.getMaxResultRows()) {
                        break;
                    }
                }
            }
        } catch (final Exception e) {
            log.error("ClickHouse MCP query execution failed: {}", e.getMessage(), e);
            final Map<String, Object> errorMap = new LinkedHashMap<>();
            errorMap.put("error", e.getMessage());
            results.add(errorMap);
        }

        return results;
    }

    public String getDatabaseName() {
        return clickhouseConfig != null && clickhouseConfig.getDatabase() != null ? clickhouseConfig.getDatabase() : "riptide";
    }
}
