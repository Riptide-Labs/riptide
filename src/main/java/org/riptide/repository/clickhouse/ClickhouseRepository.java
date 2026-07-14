/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;


import com.clickhouse.client.api.Client;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.intellij.lang.annotations.Language;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;
import org.riptide.config.ClickhouseConfig;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.data.ClickHouseColumn;
import org.riptide.repository.FlowRepository;
import org.riptide.secrets.SecretResolvers;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Slf4j
public class ClickhouseRepository implements FlowRepository {

    /**
     * The columns riptide inserts, derived from the persisted-flow POJO whose field names match
     * the ClickHouse column names 1:1. The startup schema check requires all of these to be
     * present; a table missing any is stale or mis-provisioned and fails fast (before the first
     * insert would fail opaquely).
     */
    private static final Set<String> REQUIRED_COLUMNS = Arrays.stream(ClickhouseFlow.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
            .map(Field::getName)
            .collect(Collectors.toUnmodifiableSet());

    private final FlowMapper flowMapper;

    private final ClickhouseConfig config;

    private final Client client;

    @SneakyThrows
    public ClickhouseRepository(final FlowMapper flowMapper,
                                final ClickhouseConfig config,
                                final SecretResolvers secretResolvers) {
        this.flowMapper = Objects.requireNonNull(flowMapper);
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(secretResolvers, "secretResolvers");

        // Resolve the credential SecretRefs once, before the client is built. resolve() is
        // null-safe; an unset ref falls back to the ClickHouse default user / empty password —
        // preserving the prior behaviour where an absent/blank config bound the default user (the
        // client distinguishes an empty password from a null one). An unresolvable scheme://
        // reference throws here and fails startup — a ClickHouse credential that cannot resolve is
        // fatal.
        final String resolvedUsername = secretResolvers.resolve(config.getUsername());
        final String resolvedPassword = secretResolvers.resolve(config.getPassword());
        final String username = resolvedUsername != null ? resolvedUsername : "default";
        final String password = resolvedPassword != null ? resolvedPassword : "";

        this.client = new Client.Builder()
                .addEndpoint(config.getEndpoint())
                .setUsername(username)
                .setPassword(password)
                .setDefaultDatabase(config.getDatabase())
                .compressClientRequest(true)
                .compressServerResponse(true)
                .build();
    }

    @Override
    public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
        try {
            // Persist raw flows
            this.client.insert("flows", flows.stream().map(this.flowMapper::flow).toList()).get();

        } catch (final InterruptedException | ExecutionException e) {
            throw new FlowException(e);
        }
    }

    @Override
    @SneakyThrows
    public void start() {
        if (this.config.isManageSchema()) {
            // Manage mode: ensure the schema idempotently. IF NOT EXISTS means an existing flows
            // table is not replaced, so previously persisted data survives a restart; the samples
            // VIEW holds no data and is always refreshed (OR REPLACE) so it never goes stale.
            this.client.execute(DDL_FLOWS).get();
            this.client.execute(DDL_SAMPLES).get();
        }

        // Both modes: the flows table must exist and carry every column riptide inserts. Fail-fast
        // guard (no ALTER, no migration): in manage mode it catches a stale table that IF NOT
        // EXISTS no-oped over; in validate mode it catches an absent or mis-provisioned schema —
        // before the first insert would fail with an opaque error. Reuses the schema for register.
        final TableSchema schema = checkSchema();

        this.client.register(ClickhouseFlow.class, schema);
    }

    /**
     * Verify the {@code flows} table is present and carries every column riptide inserts, throwing
     * an actionable {@link IllegalStateException} otherwise. Reads the table's own schema (not the
     * {@code system} database), so it works for a narrowly-granted writer that can describe its
     * table but not the server catalog.
     *
     * @return the table schema, reused for POJO registration
     */
    private TableSchema checkSchema() {
        final TableSchema schema;
        try {
            schema = this.client.getTableSchema("flows");
        } catch (final RuntimeException e) {
            throw new IllegalStateException(
                    "flows table not found in database '" + this.config.getDatabase()
                            + "' — provision the schema (see the ClickHouse deployment docs) or set "
                            + "riptide.clickhouse.manage-schema=true to let riptide create it.", e);
        }

        final Set<String> present = schema.getColumns().stream()
                .map(ClickHouseColumn::getColumnName)
                .collect(Collectors.toSet());
        if (present.isEmpty()) {
            throw new IllegalStateException(
                    "flows table not found in database '" + this.config.getDatabase()
                            + "' — provision the schema (see the ClickHouse deployment docs) or set "
                            + "riptide.clickhouse.manage-schema=true to let riptide create it.");
        }

        final var missing = REQUIRED_COLUMNS.stream()
                .filter(column -> !present.contains(column))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "flows table in database '" + this.config.getDatabase()
                            + "' is missing expected column(s) " + missing
                            + " — the schema is stale or mis-provisioned. Riptide performs no automatic "
                            + "migration: drop and re-provision the flows table (see the ClickHouse "
                            + "deployment docs).");
        }
        return schema;
    }

    @Language("ClickHouse")
    private static final String DDL_FLOWS = """
        CREATE TABLE IF NOT EXISTS flows (
            timestamp DateTime64(3),
    
            flowProtocol Enum8(
                'NetflowV5' = 1,
                'NetflowV9' = 2,
                'IPFIX' = 3,
                'SFLOW' = 4
            ),
    
            tenant String,
            organisation String,
            zone String,
            system String,
            exporterAddr String,
    
            receivedAt DateTime64(9),
    
            firstSwitched DateTime64(9),
            deltaSwitched DateTime64(9),
            lastSwitched DateTime64(9),
    
            inputSnmp UInt32,
            inputSnmpIfName Nullable(String),
            inputSnmpIfAlias Nullable(String),
            inputSnmpIfSpeed Nullable(UInt32),
    
            outputSnmp UInt32,
            outputSnmpIfName Nullable(String),
            outputSnmpIfAlias Nullable(String),
            outputSnmpIfSpeed Nullable(UInt32),
    
            srcAs UInt64,
            srcAsOrg Nullable(String),
            srcAddr IPv6,
            srcMaskLen UInt8,
            srcAddrHostname Nullable(String),
            srcPort UInt16,
    
            dstAs UInt64,
            dstAsOrg Nullable(String),
            dstAddr IPv6,
            dstMaskLen UInt8,
            dstAddrHostname Nullable(String),
            dstPort UInt16,
    
            nextHop Nullable(IPv6),
            nextHopHostname Nullable(String),
    
            bytes UInt64,
            packets UInt64,
    
            direction Enum8('INGRESS' = 1, 'EGRESS' = 2, 'UNKNOWN' = 3),
    
            engineId UInt32,
            engineType UInt16,
    
            vlan UInt16,
            ipProtocolVersion UInt8,
            protocol UInt8,
            tcpFlags UInt8,
            tos UInt8,
    
            samplingAlgorithm Enum8(
                'Unassigned' = 1,
                'SystematicCountBasedSampling' = 2,
                'SystematicTimeBasedSampling' = 3,
                'RandomNOutOfNSampling' = 4,
                'UniformProbabilisticSampling' = 5,
                'PropertyMatchFiltering' = 6,
                'HashBasedFiltering' = 7,
                'FlowStateDependentIntermediateFlowSelectionProcess' = 8
            ),\s
            samplingInterval Float64,
    
            application Nullable(String),
    
            srcLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
            dstLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
            flowLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
    
            clockCorrection Nullable(Int64)
        ) ENGINE = MergeTree()
        ORDER BY (
            tenant, organisation,
            toStartOfHour(timestamp),
            srcAs, dstAs,
            srcAddr, dstAddr,
            srcPort, dstPort
        )
        PARTITION BY toYYYYMMDD(timestamp)
        TTL toDateTime(timestamp) + INTERVAL 30 DAY
        SETTINGS index_granularity = 8192;
    """;

    @Language("ClickHouse")
    private static final String DDL_SAMPLES = """
        CREATE OR REPLACE VIEW samples AS
        WITH
            toInt64({ival:Int64} * 1e9) AS interval_ns,
    
            -- Find first and last absolute bucket numbers
            toUInt64(toUnixTimestamp64Nano(deltaSwitched) / interval_ns) as first_bucket,
            toUInt64(toUnixTimestamp64Nano(lastSwitched) / interval_ns) as last_bucket,
    
            last_bucket - first_bucket + 1 as bucket_count,
    
            -- Calculate total duration in nanoseconds
            age('ns', deltaSwitched, lastSwitched) as flow_duration,
    
            -- Generate buckets from the first bucket onward
            range(toUInt32(bucket_count)) AS buckets,
    
            -- Determine the fraction of the interval used in each case
            CASE
                -- Only one bucket
                WHEN bucket_count = 1
                    THEN 1.0
    
                -- First bucket: Portion from start time to the next bucket boundary
                WHEN bucket = 0
                    THEN ((interval_ns * (first_bucket + 1)) - toUnixTimestamp64Nano(deltaSwitched)) / interval_ns
    
                -- Last bucket: Portion from the start of the last bucket to end time
                WHEN bucket = bucket_count - 1
                    THEN (toUnixTimestamp64Nano(lastSwitched) - (interval_ns * last_bucket)) / interval_ns
    
                -- Full buckets in between
                ELSE 1.0
                END AS bucket_fraction
    
        SELECT
            flow.*,
    
            fromUnixTimestamp64Nano((first_bucket + bucket) * interval_ns) AS timestamp,
    
            bytes * bucket_fraction / toFloat64(bucket_count) as bytes,
            packets * bucket_fraction / toFloat64(bucket_count) as packets
    
        FROM flows AS flow
        ARRAY JOIN buckets AS bucket
        ORDER BY timestamp;
    """;

    @Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
            componentModel = "spring")
    public abstract static class FlowMapper {
        @BeanMapping(ignoreUnmappedSourceProperties = {
                "dscp",
                "ecn",
                "flowRecords",
                "flowSeqNum",
        })
        public abstract ClickhouseFlow flow(EnrichedFlow flow);

        protected Timestamp timestamp(final Instant value) {
            return Timestamp.from(value);
        }

        @SneakyThrows
        protected Inet6Address address(final InetAddress value) {
            if (value instanceof Inet6Address v6) {
                return v6;
            }

            if (value instanceof Inet4Address v4) {
                final var d = v4.getAddress();
                return Inet6Address.getByAddress(null, new byte[]{
                        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0x00, (byte) 0x00, (byte) 0xff, (byte) 0xff,
                        (byte) d[0], (byte) d[1], (byte) d[2], (byte) d[3],
                }, null);
            }

            return null;
        }

        protected byte direction(final Flow.Direction value) {
            return (byte) (value.ordinal() + 1);
        }

        protected byte samplingAlgorithm(final Flow.SamplingAlgorithm value) {
            return (byte) (value.ordinal() + 1);
        }

        protected byte protocol(final Flow.FlowProtocol value) {
            return (byte) (value.ordinal() + 1);
        }

        protected byte locality(final Flow.Locality value) {
            return (byte) (value.ordinal() + 1);
        }
    }
}
