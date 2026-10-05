/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.data.ClickHouseColumn;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which columns a row must not leave null, derived from a column list (#985).
 *
 * <p>The rule is the client's, and whether it <em>is</em> the client's is not something a unit test
 * can say: {@code UninsertableFlowsIT} measures that against a server. This pins the derivation.
 */
class RequiredColumnsTest {

    @Test
    void aNonNullableColumnWithAReferenceTypedFieldIsRequired() {
        assertThat(RequiredColumns.of(List.of(ClickHouseColumn.of("srcAddr", "IPv6"))).names())
                .containsExactly("srcAddr");
    }

    @Test
    void aNullableColumnIsNotRequired() {
        assertThat(RequiredColumns.of(List.of(
                ClickHouseColumn.of("nextHop", "Nullable(IPv6)"),
                ClickHouseColumn.of("srcAddr", "Nullable(IPv6)"))).names())
                .as("srcAddr too: an operator's relaxed column is taken as the table has it")
                .isEmpty();
    }

    @Test
    void aColumnWithADefaultIsNotRequired() {
        final ClickHouseColumn column = ClickHouseColumn.of("httpHost", "String");
        column.setHasDefault(true);

        assertThat(RequiredColumns.of(List.of(column)).names()).isEmpty();
    }

    @Test
    void aColumnBackedByAPrimitiveFieldIsNotRequired() {
        assertThat(RequiredColumns.of(List.of(ClickHouseColumn.of("srcPort", "UInt16"))).names()).isEmpty();
    }

    @Test
    void aColumnRiptideHasNoFieldForIsNotRequired() {
        assertThat(RequiredColumns.of(List.of(ClickHouseColumn.of("operatorsOwn", "String"))).names()).isEmpty();
    }

    @Test
    void theReasonNamesEveryRequiredColumnTheRowLeavesNull() throws Exception {
        final RequiredColumns required = RequiredColumns.of(List.of(
                ClickHouseColumn.of("tenant", "String"),
                ClickHouseColumn.of("srcAddr", "IPv6"),
                ClickHouseColumn.of("dstAddr", "IPv6")));
        final ClickhouseFlow row = new ClickhouseFlow();
        row.setTenant("default");

        assertThat(required.missing(row)).isEqualTo("null in non-nullable column(s) srcAddr, dstAddr");

        row.setSrcAddr((Inet6Address) InetAddress.getByName("2001:db8::1"));
        assertThat(required.missing(row)).isEqualTo("null in non-nullable column(s) dstAddr");
    }

    @Test
    void aRowThatLeavesNothingRequiredNullHasNoReason() throws Exception {
        final RequiredColumns required = RequiredColumns.of(List.of(
                ClickHouseColumn.of("srcAddr", "IPv6"),
                ClickHouseColumn.of("nextHop", "Nullable(IPv6)")));
        final ClickhouseFlow row = new ClickhouseFlow();
        row.setSrcAddr((Inet6Address) InetAddress.getByName("2001:db8::1"));

        assertThat(required.missing(row)).isNull();
    }

    @Test
    void beforeTheTableWasReadNothingIsRequired() {
        assertThat(RequiredColumns.NONE.missing(new ClickhouseFlow())).isNull();
    }
}
