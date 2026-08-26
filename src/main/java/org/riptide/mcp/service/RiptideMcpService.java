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
import java.util.Optional;
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

    /**
     * Runs a range query and, when the answering table cannot reach back to the start of that range,
     * appends one entry saying so (#609).
     *
     * <p>A query is answered from whichever table {@code QueryRouter} picks, and the result carries
     * no trace of which one that was. Where the table cannot reach the requested range, the caller
     * gets a smaller number indistinguishable from a complete one — most consequentially a 90-day
     * question answered from raw {@code flows}, whose retention is a fraction of the rollups'.</p>
     *
     * <p><b>A covered answer is returned untouched.</b> The note is only worth reading because it is
     * absent when nothing is wrong; one attached to every response is one readers learn to skip.</p>
     *
     * <p>Coverage is observed rather than assumed, and the alternatives were both worse.
     * {@code FlowsSchema.DEFAULT_TTL_DAYS} is wrong wherever an operator passed a different
     * {@code onboard --ttl-days}, which the collector never learns. {@code system.tables} has no TTL
     * column at all — it lives inside {@code create_table_query}, the unstable text this codebase
     * already refuses to compare for rollup drift. Observed coverage is also the more useful fact: a
     * TTL says what a table may keep, and a rollup that began aggregating on Tuesday holds less.</p>
     *
     * <p>Read per query and therefore per caller, so row policies scope it: a tenant is told about
     * their own earliest data rather than the deployment's.</p>
     */
    public List<Map<String, Object>> executeRangeQuery(final String sqlQuery, final String table,
                                                       final int effectiveMinutes,
                                                       final int requestedMinutes) {
        final List<Map<String, Object>> rows = executeQuery(sqlQuery);
        final Optional<Map<String, Object>> note = coverageShortfall(table, effectiveMinutes, requestedMinutes);
        if (note.isEmpty()) {
            // returned as-is, not copied: a covered answer must be exactly what the query produced
            return rows;
        }
        // a new list rather than rows.add(): executeQuery is overridable and its contract does not
        // promise a mutable result — List.of() is what the test recorder returns, and adding to it
        // throws
        final List<Map<String, Object>> annotated = new ArrayList<>(rows);
        annotated.add(note.get());
        return annotated;
    }

    /**
     * The note for a table that cannot reach back {@code timeRangeMinutes}, or empty when it can.
     *
     * <p>{@code count()} is the discriminator for an empty table, not the minimum. On an empty
     * {@code MergeTree}, {@code min(timestamp)} returns the epoch rather than null (verified on
     * 26.7), which is <em>earlier</em> than any requested start — so a check written on the minimum
     * alone reports an empty table as fully covered, the exact inverse of the truth. Holding nothing
     * and not reaching far enough are different facts; a rollup that has aggregated nothing yet is
     * the normal state of a fresh install and is not a shortfall (#587 owns that case).</p>
     *
     * <p>The comparison is done by ClickHouse rather than in Java: {@code dateDiff} against
     * {@code now()} on the server avoids parsing a timestamp out of JSON and then disagreeing with
     * the server about what "now" means.</p>
     */
    private Optional<Map<String, Object>> coverageShortfall(final String table, final int effectiveMinutes,
                                                            final int requestedMinutes) {
        final List<Map<String, Object>> probe = executeQuery(
                "SELECT count() AS row_count, toString(min(timestamp)) AS earliest,"
                        + " dateDiff('minute', min(timestamp), now()) AS covered_minutes FROM " + table);
        if (probe.size() != 1 || probe.getFirst().containsKey("error")) {
            // the probe failed, or the query it accompanies did. Saying nothing beats guessing at
            // coverage from a reading that did not happen
            return Optional.empty();
        }
        final Map<String, Object> row = probe.getFirst();
        final Long coveredMinutes = optionalLong(row.get("covered_minutes"));
        final Long rowCount = optionalLong(row.get("row_count"));
        if (coveredMinutes == null || rowCount == null) {
            // the probe answered in a shape this cannot read. Saying nothing beats failing a query
            // that succeeded, which is what parsing it eagerly used to do
            return Optional.empty();
        }
        // the answer reaches back the lesser of what the table holds and what the query actually
        // ran for. Both truncate, and comparing against the clamped window alone hid the headline
        // case entirely: a 90-day question clamped to 30 days against a 30-day table came back with
        // no warning at all, having taught the reader that no warning means nothing is wrong
        final long reach = Math.min(coveredMinutes, effectiveMinutes);
        if (!isShort(rowCount, reach, requestedMinutes)) {
            return Optional.empty();
        }

        final Map<String, Object> note = new LinkedHashMap<>();
        note.put("coverage_warning", ("answered from %s, which holds data from %s. This answer "
                + "covers %d of the %d minutes you asked for%s The rest is not missing from your "
                + "network.")
                .formatted(table, row.get("earliest"), reach, requestedMinutes,
                        effectiveMinutes < requestedMinutes
                                ? (", and riptide caps a single query at " + effectiveMinutes
                                        + " minutes.")
                                : ", which is what this table retains."));
        return Optional.of(note);
    }

    /**
     * Whether a table holding {@code rowCount} rows reaching back {@code coveredMinutes} falls short
     * of {@code requestedMinutes}.
     *
     * <p>Extracted so the empty-table rule is reachable by a test. It is currently redundant against
     * a real server and that is exactly why it needs pinning here: {@code min(timestamp)} on an empty
     * {@code MergeTree} returns the epoch, so {@code coveredMinutes} comes back around 29.8 million
     * and the range check alone already answers "covered". Deleting the row-count clause therefore
     * changes nothing observable today — a mutation of it survived the integration suite — while
     * leaving the correct answer resting entirely on that sentinel. Should the probe ever return 0 or
     * null minutes for an empty table instead, the range check alone would report every query against
     * a fresh rollup as short.</p>
     *
     * <p>So: belt and braces, with the braces pinned. Holding nothing and not reaching far enough are
     * different facts whatever the server happens to return for one of them.</p>
     */
    static boolean isShort(final long rowCount, final long coveredMinutes, final long requestedMinutes) {
        if (rowCount == 0) {
            return false;
        }
        return coveredMinutes < requestedMinutes;
    }

    /**
     * JSONEachRow gives numbers back as Integer, Long or String depending on width; null when the
     * column was null.
     *
     * <p>Returns null rather than throwing. The annotation path must never be able to fail the query
     * it annotates: a nullable {@code timestamp} column — which validate mode cannot rule out, since
     * it issues no DDL and an operator's pre-existing table shape survives — used to turn a
     * successful answer into a {@code -32603}.</p>
     */
    private static Long optionalLong(final Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public String getDatabaseName() {
        return clickhouseConfig != null && clickhouseConfig.getDatabase() != null ? clickhouseConfig.getDatabase() : "riptide";
    }
}
