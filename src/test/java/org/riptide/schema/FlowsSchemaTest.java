/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flow schema DDL is the single source shared by the collector's manage path and {@code
 * onboard}, so the load-bearing properties are that it is database-qualified (works on an unpinned
 * client), idempotent, and that the {@code samples} view still references the same qualified table.
 */
class FlowsSchemaTest {

    @Test
    void createDatabaseIsIdempotentAndQuoted() {
        assertThat(FlowsSchema.createDatabase("riptide"))
                .isEqualTo("CREATE DATABASE IF NOT EXISTS `riptide`");
    }

    @Test
    void createFlowsTableIsQualifiedAndIdempotent() {
        final String ddl = FlowsSchema.createFlowsTable("riptide");
        assertThat(ddl.strip()).startsWith("CREATE TABLE IF NOT EXISTS `riptide`.flows (");
        // A representative column and the engine/partitioning survive the extraction unchanged.
        assertThat(ddl)
                .contains("tenant String,")
                .contains("clockCorrection Nullable(Int64)")
                .contains("ENGINE = MergeTree()")
                .contains("PARTITION BY toYYYYMMDD(timestamp)");
    }

    @Test
    void timeColumnsPinUtcTimezone() {
        // The schema is timezone-explicit so stored instants display/parse in UTC regardless of the
        // server's local zone (#276) — every time column carries the 'UTC' timezone argument.
        final String ddl = FlowsSchema.createFlowsTable("riptide");
        assertThat(ddl)
                .contains("timestamp DateTime64(3, 'UTC')")
                .contains("receivedAt DateTime64(9, 'UTC')")
                .contains("firstSwitched DateTime64(9, 'UTC')")
                .contains("deltaSwitched DateTime64(9, 'UTC')")
                .contains("lastSwitched DateTime64(9, 'UTC')");
    }

    @Test
    void createSamplesViewQualifiesBothViewAndSourceTable() {
        final String ddl = FlowsSchema.createSamplesView("riptide");
        assertThat(ddl.strip()).startsWith("CREATE OR REPLACE VIEW `riptide`.samples AS");
        assertThat(ddl).contains("FROM `riptide`.flows AS flow");
        // The view parameter is a literal placeholder bound at SELECT time, not at CREATE time.
        assertThat(ddl).contains("{ival:Int64}");
    }

    @Test
    void qualifiesToTheGivenDatabase() {
        assertThat(FlowsSchema.createFlowsTable("acme_prod"))
                .contains("CREATE TABLE IF NOT EXISTS `acme_prod`.flows (");
        assertThat(FlowsSchema.createSamplesView("acme_prod"))
                .contains("VIEW `acme_prod`.samples AS")
                .contains("FROM `acme_prod`.flows AS flow");
        assertThat(FlowsSchema.qualifiedFlows("acme_prod")).isEqualTo("`acme_prod`.flows");
    }

    @Test
    void ttlIsParameterizedAndDefaultsToTheCollectorRetention() {
        assertThat(FlowsSchema.createFlowsTable("riptide", 400))
                .contains("TTL toDateTime(timestamp) + INTERVAL 400 DAY");
        // The single-arg overload (the collector's manage path) keeps the historical 30 days.
        assertThat(FlowsSchema.createFlowsTable("riptide"))
                .contains("TTL toDateTime(timestamp) + INTERVAL 30 DAY");
    }

    @Test
    void identRejectsUnsafeDatabaseNames() {
        // The collector's riptide.clickhouse.database binds without validation, so the quoting
        // site enforces the charset — a backtick must fail clearly, not emit malformed DDL.
        assertThatThrownBy(() -> FlowsSchema.createDatabase("ript`ide"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ript`ide");
        assertThatThrownBy(() -> FlowsSchema.createFlowsTable("a;b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlowsSchema.createSamplesView(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
