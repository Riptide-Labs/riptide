/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.schema;

import org.intellij.lang.annotations.Language;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The dashboard-first flow schema: the {@code traffic} table and its 1-minute rollups.
 *
 * <p>This is the stable row contract every riptide collector writes, the Java one today and any
 * later one, so a dashboard written against it keeps working across collector versions. It sits
 * beside the {@code flows} schema in {@link FlowsSchema} and shares nothing with it but the
 * database name and the retention defaults: {@code flows} stays the system of record while this
 * schema is opt-in ({@code riptide.clickhouse.traffic-table}).
 *
 * <p>Three rules make a hand-written {@code SELECT … GROUP BY} correct without knowing a protocol
 * detail:
 *
 * <ul>
 *   <li><b>{@code bytes} and {@code packets} are traffic on the wire</b>, sampling already applied,
 *       for every protocol. {@code bytes_reported} and {@code packets_reported} keep what the
 *       exporter sent. In {@code flows} a correct total needs
 *       {@code if(flowProtocol = 'SFLOW', 1, samplingInterval)}; here it is {@code sum(bytes)}.</li>
 *   <li><b>No {@code Nullable}, no {@code Enum}.</b> {@code ''}, {@code 0} and {@code '::'} mean
 *       unknown, uniformly, and every token column is {@code LowCardinality(String)} so a new value
 *       never needs a type change.</li>
 *   <li><b>Rollups spread a flow over the minutes it covers</b>, in proportion to time, inside the
 *       materialized view. A rollup minute and the raw rows of that minute therefore agree, and a
 *       minute chart needs neither the {@code samples} view nor a start-minute spike.</li>
 * </ul>
 *
 * <p>Every statement is database-qualified and idempotent ({@code IF NOT EXISTS}), like
 * {@link FlowsSchema}. Version 1 has no in-place evolution: a column added later must follow the
 * additive-only rule {@link FlowsSchema} documents, and the primary keys below are declared so a
 * later {@code MODIFY ORDER BY} cannot make a fresh and an upgraded install disagree (#470, #571).
 */
public final class TrafficSchema {

    /** The unqualified name of the raw table. */
    public static final String TRAFFIC = "traffic";

    /**
     * The longest span, in milliseconds, a rollup spreads one row across: one hour.
     *
     * <p>Exporter clocks are not trusted input. A row claiming to span a year would otherwise fan out
     * into half a million rollup rows inside the insert that carries it. A row longer than this lands
     * whole in the minute of its {@code time_start}, which is where the raw table's sort key files it
     * too. A one-hour bound is four times the longest common active timeout (15 minutes).</p>
     */
    public static final long MAX_SPREAD_MS = 3_600_000L;

    public static final String ROLLUP_BY_INTERFACE = "traffic_by_interface_1m";
    public static final String ROLLUP_BY_APPLICATION = "traffic_by_application_1m";
    public static final String ROLLUP_BY_AS = "traffic_by_as_1m";

    private TrafficSchema() {
    }

    /**
     * The columns of {@code traffic}, in table order, with their ClickHouse types. The one home for
     * the column list: the DDL is generated from it, and the collector's startup check compares the
     * live table against it.
     */
    public static Map<String, String> trafficColumns() {
        return COLUMNS;
    }

    /** {@code CREATE TABLE IF NOT EXISTS `<db>`.traffic (…)}. */
    public static String createTrafficTable(final String database, final int ttlDays) {
        final String columns = COLUMNS.entrySet().stream()
                .map(column -> "    " + column.getKey() + ' ' + column.getValue())
                .collect(Collectors.joining(",\n"));
        // PRIMARY KEY declared, not derived, for the reason FlowsSchema#rollupTable spells out: if
        // this key ever gains a column, add it to ORDER BY only and leave PRIMARY KEY as it is.
        return "CREATE TABLE IF NOT EXISTS " + FlowsSchema.qualifiedTable(database, TRAFFIC) + " (\n"
                + columns + "\n"
                + ") ENGINE = MergeTree()\n"
                + "PRIMARY KEY (" + TRAFFIC_KEY + ")\n"
                + "ORDER BY (" + TRAFFIC_KEY + ")\n"
                + "PARTITION BY toYYYYMMDD(time_start)\n"
                + "TTL toDateTime(time_start) + INTERVAL " + ttlDays + " DAY\n"
                + "SETTINGS index_granularity = 8192";
    }

    /** The rollup target-table names, in creation order. */
    public static List<String> rollupTableNames() {
        return ROLLUPS.stream().map(Rollup::table).toList();
    }

    /** The unqualified name of the materialized view feeding {@code table}. */
    public static String rollupViewName(final String table) {
        return FlowsSchema.rollupViewName(table);
    }

    /** {@code CREATE TABLE IF NOT EXISTS} for every rollup target, in {@link #rollupTableNames()} order. */
    public static List<String> createRollupTables(final String database, final int ttlDays) {
        return ROLLUPS.stream().map(rollup -> rollupTable(database, rollup, ttlDays)).toList();
    }

    /**
     * {@code CREATE MATERIALIZED VIEW IF NOT EXISTS … TO <target>} for every rollup. Emit after
     * {@link #createRollupTables}: a view cannot be created before the table its {@code TO} names.
     */
    public static List<String> createRollupViews(final String database) {
        return ROLLUPS.stream().map(rollup -> rollupView(database, rollup)).toList();
    }

    /** The columns each rollup target carries, dimensions in sort-key order then measures. */
    public static Map<String, Map<String, String>> rollupColumns() {
        final Map<String, Map<String, String>> all = new LinkedHashMap<>();
        for (final Rollup rollup : ROLLUPS) {
            final Map<String, String> columns = new LinkedHashMap<>();
            dimensions(rollup).forEach(dimension -> columns.put(dimension, dimensionType(dimension)));
            MEASURES.forEach(measure -> columns.put(measure, "UInt64"));
            all.put(rollup.table(), Collections.unmodifiableMap(columns));
        }
        return Collections.unmodifiableMap(all);
    }

    private static String rollupTable(final String database, final Rollup rollup, final int ttlDays) {
        final String columns = rollupColumns().get(rollup.table()).entrySet().stream()
                .map(column -> "    " + column.getKey() + ' ' + column.getValue())
                .collect(Collectors.joining(",\n"));
        // Every dimension is in the sort key, which is what lets SummingMergeTree collapse rows that
        // agree on all of them. PRIMARY KEY is declared for the reason given at createTrafficTable.
        final String key = String.join(", ", dimensions(rollup));
        return "CREATE TABLE IF NOT EXISTS " + FlowsSchema.qualifiedTable(database, rollup.table()) + " (\n"
                + columns + "\n"
                + ") ENGINE = SummingMergeTree()\n"
                + "PRIMARY KEY (" + key + ")\n"
                + "ORDER BY (" + key + ")\n"
                + "PARTITION BY toYYYYMM(time)\n"
                + "TTL time + INTERVAL " + ttlDays + " DAY\n"
                + "SETTINGS index_granularity = 8192";
    }

    private static String rollupView(final String database, final Rollup rollup) {
        final List<String> dimensions = dimensions(rollup);
        return "CREATE MATERIALIZED VIEW IF NOT EXISTS "
                + FlowsSchema.qualifiedTable(database, rollupViewName(rollup.table()))
                + " TO " + FlowsSchema.qualifiedTable(database, rollup.table()) + " AS\n"
                + "SELECT\n"
                + dimensions.stream().map(dimension -> "    " + dimension).collect(Collectors.joining(",\n")) + ",\n"
                + "    sum(bytes_share) AS bytes,\n"
                + "    sum(packets_share) AS packets,\n"
                + "    sum(flows_share) AS flows\n"
                + "FROM (\n" + MINUTE_SHARES
                        .replace(SOURCE_TOKEN, FlowsSchema.qualifiedTable(database, TRAFFIC))
                        .replace(MAX_SPREAD_TOKEN, Long.toString(MAX_SPREAD_MS)) + ")\n"
                + "GROUP BY " + String.join(", ", dimensions);
    }

    private static List<String> dimensions(final Rollup rollup) {
        return Stream.concat(PREAMBLE.stream(), rollup.dimensions().stream()).toList();
    }

    private static String dimensionType(final String dimension) {
        if ("time".equals(dimension)) {
            return "DateTime('UTC')";
        }
        final String type = COLUMNS.get(dimension);
        if (type == null) {
            throw new IllegalStateException("rollup dimension " + dimension + " is not a traffic column");
        }
        return type;
    }

    private record Rollup(String table, List<String> dimensions) {
    }

    private static final String SOURCE_TOKEN = "@@traffic@@";
    private static final String MAX_SPREAD_TOKEN = "@@maxSpreadMs@@";

    /**
     * One row per (traffic row, minute it covers), carrying that minute's integer share of the row's
     * {@code bytes}, {@code packets} and its one flow.
     *
     * <p><b>Shares are cut, not rounded.</b> A row spanning {@code [start, start + span)} gives the
     * minute covering {@code [lo, hi)} of it {@code floor(x·hi/span) − floor(x·lo/span)}. Summed over
     * the row's minutes this telescopes to {@code floor(x·span/span) − 0 = x}, so a rollup's total
     * equals the raw total exactly, with no float in sight. Rounding each share instead would let
     * the minutes of one row sum to one more or one less than the row. The products go through
     * {@code UInt128} because {@code bytes × span} overflows {@code UInt64} at about 5 TB in an hour.</p>
     *
     * <p><b>A flow counts once, in the minute it ends.</b> The same cut applied to {@code x = 1} is
     * {@code 0} in every minute but the last, so {@code sum(flows)} over any range counts each row
     * once.</p>
     *
     * <p>A zero-length row (sFlow samples, a single-packet flow) and a row longer than
     * {@link #MAX_SPREAD_MS} land whole in the minute of {@code time_start}. A row whose
     * {@code time_end} precedes its {@code time_start} is a clock problem at the exporter and is
     * treated as zero-length rather than producing a negative span. The one-millisecond shift on
     * {@code last_minute} keeps a row ending exactly on a minute boundary from emitting an all-zero
     * row for the next minute. That is economy, not correctness: {@code SummingMergeTree} drops an
     * all-zero row on insert ({@code optimize_on_insert}, on by default) and on merge, so querying a
     * rollup cannot tell the shift is there.</p>
     */
    @Language("ClickHouse")
    private static final String MINUTE_SHARES = """
        SELECT
            *,
            toDateTime(intDiv((first_minute + minute_index) * 60000, 1000), 'UTC') AS time,
            greatest(start_ms, (first_minute + minute_index) * 60000) - start_ms AS cut_lo,
            least(start_ms + span_ms, (first_minute + minute_index + 1) * 60000) - start_ms AS cut_hi,
            if(span_ms = 0, bytes,
                toUInt64(intDiv(toUInt128(bytes) * cut_hi, span_ms) - intDiv(toUInt128(bytes) * cut_lo, span_ms))) AS bytes_share,
            if(span_ms = 0, packets,
                toUInt64(intDiv(toUInt128(packets) * cut_hi, span_ms) - intDiv(toUInt128(packets) * cut_lo, span_ms))) AS packets_share,
            if(span_ms = 0, 1, toUInt64(intDiv(cut_hi, span_ms) - intDiv(cut_lo, span_ms))) AS flows_share
        FROM (
            SELECT
                *,
                toUnixTimestamp64Milli(time_start) AS start_ms,
                toUnixTimestamp64Milli(time_end) - start_ms AS raw_span_ms,
                if(raw_span_ms > 0 AND raw_span_ms <= @@maxSpreadMs@@, raw_span_ms, 0) AS span_ms,
                intDiv(start_ms, 60000) AS first_minute,
                intDiv(start_ms + span_ms - if(span_ms > 0, 1, 0), 60000) AS last_minute
            FROM @@traffic@@
        )
        ARRAY JOIN range(toUInt64(last_minute - first_minute + 1)) AS minute_index
        """;

    /** Every rollup leads with these, so a tenant, organisation and time filter prunes all of them. */
    private static final List<String> PREAMBLE = List.of("tenant", "organisation", "time", "zone");

    private static final List<String> MEASURES = List.of("bytes", "packets", "flows");

    /**
     * Each rollup answers one family of dashboard panels. Point-in-time names travel with their ids,
     * so a panel can label a series without a join; a renamed interface simply starts a new series
     * from the minute it was renamed.
     */
    private static final List<Rollup> ROLLUPS = List.of(
            new Rollup(ROLLUP_BY_INTERFACE, List.of(
                    "exporter_ip", "exporter_name",
                    "in_if", "in_if_name", "out_if", "out_if_name",
                    "direction")),
            new Rollup(ROLLUP_BY_APPLICATION, List.of(
                    "exporter_ip", "application", "proto")),
            new Rollup(ROLLUP_BY_AS, List.of(
                    "src_as", "src_as_name", "dst_as", "dst_as_name",
                    "src_country", "dst_country")));

    /**
     * Leads with tenant and organisation (the row-policy and CHECK columns of every riptide table),
     * then the hour, then exporter and interface because most dashboard panels filter on those. The
     * {@code flows} key leads with AS and addresses instead; which serves real panels better is a
     * judgement, not a measurement, and is the first thing to check on lab data.
     */
    private static final String TRAFFIC_KEY =
            "tenant, organisation, toStartOfHour(time_start), exporter_ip, in_if, src_addr, dst_addr";

    private static final Map<String, String> COLUMNS;

    static {
        final Map<String, String> columns = new LinkedHashMap<>();
        // Who and where. tenant and organisation are what the tenancy CHECK and row policies of
        // every riptide table pin.
        columns.put("tenant", "LowCardinality(String)");
        columns.put("organisation", "LowCardinality(String)");
        columns.put("zone", "LowCardinality(String)");
        columns.put("system", "LowCardinality(String)");

        // When, all in UTC. time_start..time_end is the interval this row's counters cover (for a
        // long-lived flow under an active timeout, the interval since the previous export), which is
        // what the rollups spread over. flow_start is when the flow itself began.
        columns.put("time_start", "DateTime64(3, 'UTC')");
        columns.put("time_end", "DateTime64(3, 'UTC')");
        columns.put("flow_start", "DateTime64(3, 'UTC')");
        columns.put("time_received", "DateTime64(3, 'UTC')");

        // The exporter. IPv4 is stored IPv4-mapped (::ffff:a.b.c.d), as every address column is.
        columns.put("exporter_ip", "IPv6");
        columns.put("exporter_name", "LowCardinality(String)");
        columns.put("flow_protocol", "LowCardinality(String)");
        columns.put("observation_domain", "UInt32");

        // Interfaces, as SNMP ifIndex with the names and speed known when the flow was enriched.
        columns.put("in_if", "UInt32");
        columns.put("in_if_name", "LowCardinality(String)");
        columns.put("in_if_alias", "LowCardinality(String)");
        columns.put("in_if_speed", "UInt64");
        columns.put("out_if", "UInt32");
        columns.put("out_if_name", "LowCardinality(String)");
        columns.put("out_if_alias", "LowCardinality(String)");
        columns.put("out_if_speed", "UInt64");
        columns.put("direction", "LowCardinality(String)");

        // Endpoints.
        columns.put("src_addr", "IPv6");
        columns.put("src_port", "UInt16");
        columns.put("src_mask", "UInt8");
        columns.put("src_as", "UInt32");
        columns.put("src_as_name", "LowCardinality(String)");
        columns.put("src_country", "LowCardinality(String)");
        columns.put("src_city", "LowCardinality(String)");
        columns.put("src_host", "String");
        columns.put("src_locality", "LowCardinality(String)");
        columns.put("dst_addr", "IPv6");
        columns.put("dst_port", "UInt16");
        columns.put("dst_mask", "UInt8");
        columns.put("dst_as", "UInt32");
        columns.put("dst_as_name", "LowCardinality(String)");
        columns.put("dst_country", "LowCardinality(String)");
        columns.put("dst_city", "LowCardinality(String)");
        columns.put("dst_host", "String");
        columns.put("dst_locality", "LowCardinality(String)");
        columns.put("next_hop", "IPv6");

        // Packet header.
        columns.put("ip_version", "UInt8");
        columns.put("proto", "UInt8");
        columns.put("tcp_flags", "UInt8");
        columns.put("tos", "UInt8");
        columns.put("vlan", "UInt16");

        // Application.
        columns.put("application", "LowCardinality(String)");
        columns.put("application_source", "LowCardinality(String)");
        columns.put("application_id", "UInt32");
        columns.put("http_host", "String");
        columns.put("http_uri", "String");

        // Volume. bytes and packets are the estimate on the wire; the _reported pair is what the
        // exporter sent. sampling_rate is the factor between them, sampling_source the rung of the
        // resolution ladder that supplied it.
        columns.put("bytes", "UInt64");
        columns.put("packets", "UInt64");
        columns.put("bytes_reported", "UInt64");
        columns.put("packets_reported", "UInt64");
        columns.put("sampling_rate", "Float64");
        columns.put("sampling_source", "LowCardinality(String)");
        COLUMNS = Collections.unmodifiableMap(columns);
    }
}
