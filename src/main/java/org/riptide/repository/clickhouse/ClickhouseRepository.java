/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;


import com.clickhouse.client.api.Client;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretResolvers;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    // Retained so manage mode can open a second, unpinned client to create the database.
    private final String username;
    private final String password;

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
        this.username = resolvedUsername != null ? resolvedUsername : "default";
        this.password = resolvedPassword != null ? resolvedPassword : "";

        this.client = new Client.Builder()
                .addEndpoint(config.getEndpoint())
                .setUsername(this.username)
                .setPassword(this.password)
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
            // Manage mode: ensure the schema idempotently. The database comes first — the main
            // client pins it via setDefaultDatabase, so DDL through it fails with UNKNOWN_DATABASE
            // on a fresh server. Then IF NOT EXISTS means an existing flows table is not replaced,
            // so previously persisted data survives a restart; the samples VIEW holds no data and is
            // always refreshed (OR REPLACE) so it never goes stale.
            ensureDatabase();
            this.client.execute(FlowsSchema.createFlowsTable(this.config.getDatabase())).get();
            this.client.execute(FlowsSchema.createSamplesView(this.config.getDatabase())).get();
            // Additive upgrades manage mode owns: a pre-existing table that IF NOT EXISTS no-oped
            // over gains the additive columns in place (no data loss); on a fresh table these no-op.
            for (final String ddl : FlowsSchema.addAdditiveColumns(this.config.getDatabase())) {
                this.client.execute(ddl).get();
            }
        }

        // Both modes: the flows table must exist and carry every column riptide inserts. Fail-fast
        // guard (no ALTER, no migration): in manage mode it catches a stale table that IF NOT
        // EXISTS no-oped over; in validate mode it catches an absent or mis-provisioned schema —
        // before the first insert would fail with an opaque error. Reuses the schema for register.
        final TableSchema schema = checkSchema();

        if (this.config.isManageSchema()) {
            // Rollups come after the flows check, not with the DDL above: their materialized views
            // select from flows, so creating them against a stale or mis-provisioned table would
            // fail with an error about the view rather than about the real problem. Targets before
            // views — a view cannot be created before the table its TO clause names.
            for (final String ddl : FlowsSchema.createRollupTables(this.config.getDatabase())) {
                this.client.execute(ddl).get();
            }
            for (final String ddl : FlowsSchema.createRollupViews(this.config.getDatabase())) {
                this.client.execute(ddl).get();
            }
        }

        this.client.register(ClickhouseFlow.class, schema);
    }

    /**
     * Create the target database if it is absent. The main client is scoped to the configured
     * database via {@code setDefaultDatabase}, so a {@code CREATE DATABASE} through it is rejected
     * with {@code UNKNOWN_DATABASE} when the database does not exist yet. This opens a short-lived
     * client that is not scoped to it (the always-present {@code default} database), so the
     * statement runs. Manage mode owns the schema, so creating the database is part of that.
     */
    @SneakyThrows
    private void ensureDatabase() {
        try (Client bootstrap = new Client.Builder()
                .addEndpoint(this.config.getEndpoint())
                .setUsername(this.username)
                .setPassword(this.password)
                .setDefaultDatabase("default")
                .compressClientRequest(true)
                .compressServerResponse(true)
                .build()) {
            bootstrap.execute(FlowsSchema.createDatabase(this.config.getDatabase())).get();
        }
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
            if (FlowsSchema.additiveColumnNames().containsAll(missing)) {
                // Only additive columns are missing — in validate mode riptide never
                // alters the schema, but the fix is a safe, data-preserving onboard re-run.
                throw new IllegalStateException(
                        "flows table in database '" + this.config.getDatabase()
                                + "' is missing the column(s) " + missing
                                + " — re-run 'riptide onboard' to add them in place (no data loss), or set "
                                + "riptide.clickhouse.manage-schema=true to let riptide add them.");
            }
            throw new IllegalStateException(
                    "flows table in database '" + this.config.getDatabase()
                            + "' is missing expected column(s) " + missing
                            + " — the schema is stale or mis-provisioned. Riptide performs no automatic "
                            + "migration: drop and re-provision the flows table (see the ClickHouse "
                            + "deployment docs).");
        }
        return schema;
    }

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

        protected OffsetDateTime timestamp(final Instant value) {
            // UTC offset so the client-v2 DateTime64 encoding is an absolute instant, independent
            // of the collector host's timezone (#276).
            return value.atOffset(ZoneOffset.UTC);
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
