/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.service;

import com.clickhouse.client.api.Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.mcp.config.McpProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage reporting against a real server (#609).
 *
 * <p>Driven through ClickHouse rather than a stub because the whole change turns on what the server
 * actually returns. In particular {@code min(timestamp)} on an empty {@code MergeTree} returns the
 * <em>epoch</em>, not null — which is earlier than any requested start, so a check written on the
 * minimum alone reports an empty table as fully covered. That is the inverse of the truth and no
 * stub would have revealed it.</p>
 */
@Testcontainers
public class CoverageReportingIT {

    private static final String DATABASE = "coverage";

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static String endpoint;
    private static Client admin;

    @BeforeAll
    static void bootstrap() throws Exception {
        endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
        admin = new Client.Builder().addEndpoint(endpoint)
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase("default").build();
        admin.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE).get();
    }

    @BeforeEach
    void freshTable() throws Exception {
        admin.execute("DROP TABLE IF EXISTS " + DATABASE + ".flows").get();
        admin.execute("CREATE TABLE " + DATABASE + ".flows ("
                + "timestamp DateTime64(3,'UTC'), bytes UInt64) "
                + "ENGINE = MergeTree PARTITION BY toYYYYMMDD(timestamp) ORDER BY timestamp").get();
    }

    private static RiptideMcpService service() {
        final var config = new ClickhouseConfig();
        config.setDatabase(DATABASE);
        final var client = new Client.Builder().addEndpoint(endpoint)
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase(DATABASE).build();
        return new RiptideMcpService(client, config, new McpProperties(), new ObjectMapper());
    }

    private static void insertDaysAgo(final int... days) throws Exception {
        for (final int day : days) {
            admin.execute("INSERT INTO " + DATABASE + ".flows VALUES (now() - INTERVAL " + day
                    + " DAY, 1)").get();
        }
    }

    private static final String COUNT_SQL = "SELECT count() AS c FROM " + DATABASE + ".flows";
    private static final String TABLE = DATABASE + ".flows";

    /** 3 days of data, 90 days asked for: the answer is short and has to say so. */
    @Test
    void aRangeBeyondTheDataIsReported() throws Exception {
        insertDaysAgo(3, 1);

        final List<Map<String, Object>> rows =
                service().executeRangeQuery(COUNT_SQL, TABLE, 90 * 24 * 60, 90 * 24 * 60);

        assertThat(rows).hasSize(2);
        assertThat(String.valueOf(rows.get(1).get("coverage_warning")))
                .contains(TABLE)
                .contains("of the " + (90 * 24 * 60) + " minutes you asked for");
    }

    /** The property worth pinning hardest: a covered answer is exactly the rows and nothing else. */
    @Test
    void aCoveredRangeIsReturnedUntouched() throws Exception {
        insertDaysAgo(30, 1);

        final List<Map<String, Object>> rows =
                service().executeRangeQuery(COUNT_SQL, TABLE, 15, 15);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).doesNotContainKey("coverage_warning");
    }

    /**
     * An empty table holds nothing; that is not the same as not reaching far enough.
     *
     * <p>The trap this exists for: {@code min(timestamp)} returns {@code 1970-01-01} here rather than
     * null, so a shortfall check written on the minimum alone concludes the table reaches back to the
     * epoch and reports full coverage. {@code count()} is the discriminator. A rollup that has
     * aggregated nothing yet is the normal state of a fresh install, and #587 owns the separate
     * question of an empty rollup reading like an answer.</p>
     */
    @Test
    void anEmptyTableIsNotReportedAsShort() {
        final List<Map<String, Object>> rows =
                service().executeRangeQuery(COUNT_SQL, TABLE, 90 * 24 * 60, 90 * 24 * 60);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).doesNotContainKey("coverage_warning");
    }

    /**
     * A request capped by riptide's own limit is reported, even when the table covers the capped
     * window completely.
     *
     * <p>This is the case the first version missed entirely, and the miss was worse than silence: a
     * 90-day question is capped to 30 days before it reaches here, so comparing coverage against the
     * capped window found nothing short and returned no note — on a page that had just taught the
     * reader that no note means nothing is wrong. The earlier IT could not catch it because it called
     * this method directly, bypassing the cap that lives in {@code ToolParams}.</p>
     */
    @Test
    void aRequestCappedByRiptideIsReportedEvenWhenTheTableCoversTheCappedWindow() throws Exception {
        insertDaysAgo(40, 1);

        final List<Map<String, Object>> rows =
                service().executeRangeQuery(COUNT_SQL, TABLE, 43_200, 129_600);

        assertThat(rows).hasSize(2);
        assertThat(String.valueOf(rows.get(1).get("coverage_warning")))
                .as("the table covers the capped window, so only the cap makes this short")
                .contains("43200 of the 129600 minutes you asked for")
                .contains("riptide caps a single query");
    }

    /**
     * The annotation path must never fail the query it annotates.
     *
     * <p>Validate mode issues no DDL and table creation is {@code IF NOT EXISTS}, so an operator's
     * pre-existing shape survives — including a nullable {@code timestamp}. With every row null the
     * probe returns a null {@code covered_minutes}, and parsing that eagerly threw
     * {@code NumberFormatException} out through {@code tool.execute}, turning a successful answer
     * into a {@code -32603}. Saying nothing beats failing a query that worked.</p>
     */
    @Test
    void aProbeThatCannotBeReadDoesNotFailTheAnswer() throws Exception {
        admin.execute("DROP TABLE IF EXISTS " + DATABASE + ".nullable").get();
        admin.execute("CREATE TABLE " + DATABASE + ".nullable ("
                + "timestamp Nullable(DateTime64(3,'UTC')), bytes UInt64) "
                + "ENGINE = MergeTree ORDER BY tuple()").get();
        admin.execute("INSERT INTO " + DATABASE + ".nullable VALUES (NULL, 1)").get();

        final List<Map<String, Object>> rows = service().executeRangeQuery(
                "SELECT count() AS c FROM " + DATABASE + ".nullable",
                DATABASE + ".nullable", 43_200, 129_600);

        assertThat(rows)
                .as("the answer survives a probe it cannot read")
                .hasSize(1);
        assertThat(rows.getFirst()).doesNotContainKey("error");
    }

    /**
     * Coverage is observed, so a retention shorter than riptide's compiled-in default is reported
     * accurately rather than as the default.
     *
     * <p>{@code DEFAULT_TTL_DAYS} is 30. This table holds 5 days, and a 10-day question must be told
     * about 5 — the number the data supports — not waved through because 10 is under 30.</p>
     */
    @Test
    void aShorterActualRetentionIsReportedFromTheDataNotTheDefault() throws Exception {
        insertDaysAgo(5);

        final List<Map<String, Object>> rows =
                service().executeRangeQuery(COUNT_SQL, TABLE, 10 * 24 * 60, 10 * 24 * 60);

        assertThat(rows).hasSize(2);
        assertThat(String.valueOf(rows.get(1).get("coverage_warning")))
                .as("a 10-day question against 5 days of data is short, whatever the default says")
                .contains("minutes you asked for");
    }
}
