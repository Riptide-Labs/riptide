/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the {@code traffic} DDL must hold as text. What it does on a server, the spreading above
 * all, is {@code TrafficTableIT}'s job.
 */
class TrafficSchemaTest {

    private static final Pattern PRIMARY_KEY = Pattern.compile("PRIMARY KEY \\((.*)\\)\nORDER BY \\((.*)\\)\n");

    @Test
    void everyStatementIsQualifiedAndIdempotent() {
        final List<String> statements = all();

        assertThat(statements).hasSize(1 + 2 * TrafficSchema.rollupTableNames().size());
        assertThat(statements.getFirst()).startsWith("CREATE TABLE IF NOT EXISTS `riptide`.traffic (");
        assertThat(statements).allSatisfy(ddl -> assertThat(ddl).contains("IF NOT EXISTS `riptide`."));
        // Targets before views: a view cannot be created before the table its TO clause names.
        assertThat(statements.get(1)).startsWith("CREATE TABLE");
        assertThat(statements.getLast()).startsWith("CREATE MATERIALIZED VIEW");
    }

    @Test
    void noPlaceholderSurvivesIntoTheDdl() {
        assertThat(all())
                .allSatisfy(ddl -> assertThat(ddl).doesNotContain("@@"));
        assertThat(TrafficSchema.createRollupViews("riptide"))
                .allSatisfy(ddl -> assertThat(ddl)
                        .contains("FROM `riptide`.traffic")
                        .contains("raw_span_ms <= " + TrafficSchema.MAX_SPREAD_MS + ","));
    }

    /**
     * Declared, never derived: a later {@code MODIFY ORDER BY} grows the sorting key alone, so a
     * derived primary key is how fresh and upgraded installs come to disagree (#470, #571).
     */
    @Test
    void everyTableDeclaresAPrimaryKeyEqualToItsSortKey() {
        final List<String> tables = new ArrayList<>();
        tables.add(TrafficSchema.createTrafficTable("riptide", 30));
        tables.addAll(TrafficSchema.createRollupTables("riptide", 365));

        for (final String ddl : tables) {
            final Matcher keys = PRIMARY_KEY.matcher(ddl);
            assertThat(keys.find()).as(ddl).isTrue();
            assertThat(keys.group(1)).isEqualTo(keys.group(2));
        }
    }

    @Test
    void retentionComesFromTheCaller() {
        assertThat(TrafficSchema.createTrafficTable("riptide", 7))
                .contains("TTL toDateTime(time_start) + INTERVAL 7 DAY");
        assertThat(TrafficSchema.createRollupTables("riptide", 400))
                .allSatisfy(ddl -> assertThat(ddl).contains("TTL time + INTERVAL 400 DAY"));
    }

    /** The rule the whole table exists for: nothing a dashboard sums or groups by can be NULL or an enum. */
    @Test
    void noColumnIsNullableOrAnEnum() {
        assertThat(TrafficSchema.trafficColumns().values())
                .noneMatch(type -> type.contains("Nullable") || type.contains("Enum"));
        TrafficSchema.rollupColumns().values().forEach(columns -> assertThat(columns.values())
                .noneMatch(type -> type.contains("Nullable") || type.contains("Enum")));
    }

    /** A rollup dimension is the raw column of the same name and type, so a query ports between them. */
    @Test
    void rollupDimensionsCarryTheRawColumnsType() {
        TrafficSchema.rollupColumns().forEach((rollup, columns) -> columns.forEach((column, type) -> {
            if ("time".equals(column) || List.of("bytes", "packets", "flows").contains(column)) {
                return;
            }
            assertThat(TrafficSchema.trafficColumns()).as(rollup).containsEntry(column, type);
        }));
    }

    /** Every statement in the order the collector runs them: the table, the targets, the views. */
    private static List<String> all() {
        final List<String> statements = new ArrayList<>();
        statements.add(TrafficSchema.createTrafficTable("riptide", 30));
        statements.addAll(TrafficSchema.createRollupTables("riptide", 365));
        statements.addAll(TrafficSchema.createRollupViews("riptide"));
        return statements;
    }

    @Test
    void theDatabaseNameIsCharsetChecked() {
        assertThatThrownBy(() -> TrafficSchema.createTrafficTable("a`b", 30))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
