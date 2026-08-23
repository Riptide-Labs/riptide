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
import org.riptide.schema.RollupAvailability;
import org.riptide.schema.RollupShapeCheck;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        final var builder = new Client.Builder()
                .addEndpoint(config.getEndpoint())
                .setUsername(this.username)
                .setPassword(this.password)
                .setDefaultDatabase(config.getDatabase())
                // Request compression is the insert path and costs flusher CPU, so it is
                // configurable (see ClickhouseConfig#compressRequests). Response compression only
                // affects the schema queries at startup, so it stays on unconditionally.
                .compressClientRequest(config.isCompressRequests())
                .compressServerResponse(true);
        if (config.isAsyncInserts()) {
            // Server-side coalescing, now opt-in: client-side batching supersedes it (see
            // ClickhouseConfig#asyncInserts for the trade-off and measurements).
            // wait_for_async_insert=0 acknowledges on buffer append — waiting for the flush would
            // serialize the pipeline on the flush interval, which benchmarks slower than not
            // coalescing at all (14 vs 56 inserts/s).
            builder.serverSetting("async_insert", "1")
                    .serverSetting("wait_for_async_insert", "0");
        }
        this.client = builder.build();
    }

    @Override
    public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
        try {
            // Persist raw flows
            this.client.insert("flows", flows.stream().map(this.flowMapper::flow).toList()).get();

        } catch (final InterruptedException e) {
            // Restore the flag before wrapping: the batching flusher swallows FlowException (a
            // poison batch must not wedge it) and relies on the thread's interrupt status to
            // observe a shutdown-drain interrupt.
            Thread.currentThread().interrupt();
            throw new FlowException(e);
        } catch (final ExecutionException e) {
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

        // Rollups whose repair did not complete on this start. Every statement below is guarded, so
        // one rollup's failure cannot stop the others or stop ingestion — but a rollup left
        // half-repaired must not keep answering queries either, and the shape check alone will not
        // always catch it (a target created current with no view reads as UNVERIFIABLE, which is
        // deliberately NOT declined). What this start could not fix, it declines.
        final Set<String> unrepaired = new LinkedHashSet<>();

        if (this.config.isManageSchema()) {
            // Rollups come after the flows check, not with the DDL above: their materialized views
            // select from flows, so creating them against a stale or mis-provisioned table would
            // fail with an error about the view rather than about the real problem. Targets before
            // views — a view cannot be created before the table its TO clause names.
            for (final String ddl : FlowsSchema.createRollupTables(this.config.getDatabase())) {
                this.client.execute(ddl).get();
            }

            // Targets are repaired BEFORE the views are created, not after. CREATE MATERIALIZED
            // VIEW IF NOT EXISTS validates its SELECT against the target even when the view already
            // exists and the statement would no-op — so on the first start after a rollup gains a
            // dimension, creating the view would fail with "SELECT query outputs column with name
            // 'x', which is not found in the target table" before any repair had a chance to run.
            //
            // Guarded per rollup, and a failure is remembered rather than propagated. This is the
            // statement that actually changes a target's shape, so it is the most likely of the
            // three to be rejected — a manage-mode user without ALTER on the target, or MODIFY
            // ORDER BY's exclusive metadata lock timing out against concurrent inserts. Unguarded
            // it does not merely skip one rollup: it aborts start(), so no view is created, no view
            // repaired, no shape verified, and the collector does not come up at all. That is the
            // ingestion outage over a rollup-only concern that verifyRollupShapes exists to rule
            // out, and it does not stop being one because the failing statement is a repair.
            final Map<String, String> alters = FlowsSchema.alterRollupTargets(this.config.getDatabase());
            for (final String rollup : planTargetRepair()) {
                repair(unrepaired, rollup, alters.get(rollup),
                        "target could not be brought up to date");
            }

            // Per rollup, and tolerant, because the SELECT now names a column an unrepaired target
            // can lack. CREATE MATERIALIZED VIEW IF NOT EXISTS validates its SELECT even when it
            // no-ops, so a rollup the planner REFUSED — or one whose repair was deferred by a
            // failed catalog read — would otherwise throw here and take ingestion down for a
            // rollup-only concern. That is the outage verifyRollupShapes exists to avoid, and
            // before the rate was appended it could not happen: no rollup SELECT named a column an
            // unrepaired target could be missing.
            for (final Map.Entry<String, String> view : FlowsSchema.createRollupViewsByRollup(
                    this.config.getDatabase()).entrySet()) {
                repair(unrepaired, view.getKey(), view.getValue(),
                        "materialized view could not be created against its current target");
            }

            // And the views last: MODIFY QUERY swaps an existing view's SELECT in place, which
            // CREATE ... IF NOT EXISTS cannot do. Repairing before verifying means a rollup brought
            // up to date on this start verifies clean rather than warning once per upgrade.
            //
            // Planned SEPARATELY from the targets, against the views' own live SELECT. Deriving it
            // from the target's shape would strand a view permanently: if a target ALTER succeeds
            // and anything before its MODIFY QUERY throws, the next start sees a target that is
            // already current, plans no repair for it, and never fixes the view.
            final Map<String, String> modifies = FlowsSchema.modifyRollupViews(this.config.getDatabase());
            for (final String rollup : planViewRepair()) {
                repair(unrepaired, rollup, modifies.get(rollup), "materialized view could not be repaired");
            }
        }

        // Both modes: report a rollup whose shape is not what this version intends, and keep the
        // query path off it. Runs last because in manage mode the CREATEs and the repair above are
        // what a fresh or upgraded install's shape comes from. Never fails startup — see
        // verifyRollupShapes.
        verifyRollupShapes(unrepaired);

        this.client.register(ClickhouseFlow.class, schema);
    }

    /**
     * Run one repair statement for one rollup; on failure, warn and mark the rollup unrepaired.
     *
     * <p>Never propagates. A rollup is a derived, rebuildable table and raw {@code flows} answers
     * every query it would have — so no failure here justifies refusing to collect. The cost of
     * giving up on one rollup for one start is a slower long-range query; the cost of propagating
     * is flows that are never collected, and those do not come back.</p>
     *
     * @param unrepaired collects the rollups the query path must avoid, since a rollup this start
     *                   could not repair may be in any state — including one the shape check does
     *                   not classify as drifted
     */
    private void repair(final Set<String> unrepaired, final String rollup, final String ddl,
            final String what) {
        try {
            this.client.execute(ddl).get();
        } catch (final InterruptedException e) {
            // Startup is being torn down; the flag is the only correct response. Same discipline as
            // persist() and verifyRollupShapes() — a swallowed interrupt during shutdown leaves the
            // thread running work nobody is waiting for.
            Thread.currentThread().interrupt();
            unrepaired.add(rollup);
        } catch (final Exception e) {
            log.warn("Rollup {}: {}: {}. Ingestion is unaffected; long-range queries fall back to raw"
                    + " flows until it is repaired.", rollup, what, e.getMessage());
            unrepaired.add(rollup);
        }
    }

    /**
     * Which rollup targets need their dimensions or sorting key brought up to date (#470).
     *
     * <p>The decision itself lives in {@link FlowsSchema#planRollupRepair}, shared with
     * {@code onboard} so the two paths cannot disagree about what is safe to do in place.</p>
     *
     * <p>Reading the live shape must never fail startup. The statements are idempotent, so a
     * catalog read that fails costs a deferred repair and nothing else — whereas propagating would
     * turn a rollup-only concern into an ingestion outage, which is the same rule
     * {@link #verifyRollupShapes} states at length.</p>
     */
    private List<String> planTargetRepair() {
        final FlowsSchema.RepairPlan plan;
        try {
            plan = FlowsSchema.planRollupRepair(readRollupSortKeys(), readRollupColumnNames());
        } catch (final Exception e) {
            log.warn("Could not read the rollup shapes in database '{}': {}. Ingestion is unaffected;"
                    + " any pending repair is deferred to the next start.",
                    this.config.getDatabase(), e.getMessage());
            return List.of();
        }
        plan.refused().forEach((rollup, why) -> log.warn("Rollup {} left as it is: {}.", rollup, why));
        plan.repair().forEach(rollup -> log.info("Rollup {}: appending this version's dimensions in place.", rollup));
        return plan.repair();
    }

    /**
     * Which rollup views select a different <em>column set</em> than this version emits.
     *
     * <p>Planned from the view's own {@code as_select} rather than from its target's shape, so a
     * view left behind by a half-applied repair — target altered, {@code MODIFY QUERY} not reached —
     * is picked up on the next start instead of being stranded forever.</p>
     *
     * <p><b>Column set, not text.</b> A view whose columns match but whose expression differs is a
     * corrected aggregate, and repairing that is deliberately out of scope: it would readmit rows
     * computed the old way with nothing distinguishing them, which is worse than the declined
     * rollup {@link #verifyRollupShapes} already gives. The two cases look identical as strings and
     * are entirely different in what they mean.</p>
     *
     * <p><b>Growth only.</b> A view selecting columns this version does not know is a downgrade, and
     * is refused: see the comment on that branch. The target keeps the column either way, so the
     * only thing a repair would achieve is writing type defaults over live rows.</p>
     */
    private List<String> planViewRepair() {
        final Map<String, String> live;
        try {
            live = readRollupSelects();
        } catch (final Exception e) {
            return List.of();
        }
        final List<String> stale = new ArrayList<>();
        FlowsSchema.rollupSelects(this.config.getDatabase()).forEach((rollup, intended) -> {
            final String current = live.get(rollup + "_mv");
            if (current == null) {
                return;
            }
            final Set<String> now = outputColumns(current);
            final Set<String> wanted = outputColumns(intended);
            if (now.equals(wanted)) {
                return;
            }
            if (!wanted.containsAll(now)) {
                // A DOWNGRADE, and the one direction that silently destroys data rather than
                // withholding it. Rolling back to a version that knows fewer dimensions leaves the
                // target's column in place (the old planner refuses the sorting-key shrink) while
                // this MODIFY QUERY would re-point the view at a SELECT that no longer names it —
                // and ClickHouse accepts that without complaint, dropping the column on every
                // insert. Verified on 26.7: MODIFY QUERY does NOT validate against its target.
                //
                // Every row aggregated from then on takes the column's type default, and for
                // samplingInterval that default is 0, the one value reserved to mean "written
                // before the column existed". Real traffic would become indistinguishable from
                // pre-append rows, permanently, and a later re-upgrade could not tell them apart.
                // Declining the rollup is recoverable; writing the sentinel over live data is not.
                log.warn("Rollup {}'s materialized view selects columns this version does not ({}),"
                        + " which means a downgrade. Leaving it alone and routing around it: taking"
                        + " the columns away would write this version's defaults over rows that"
                        + " mean something else.", rollup, difference(now, wanted));
                return;
            }
            stale.add(rollup);
        });
        return stale;
    }

    /** The names in {@code now} that {@code wanted} does not have, for the log line above. */
    private static Set<String> difference(final Set<String> now, final Set<String> wanted) {
        final Set<String> extra = new LinkedHashSet<>(now);
        extra.removeAll(wanted);
        return extra;
    }

    /** The names a SELECT emits, which is what a target table has to carry. */
    private static Set<String> outputColumns(final String select) {
        final Matcher matcher = SELECT_ALIAS.matcher(RollupShapeCheck.normalise(select));
        final Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static final Pattern SELECT_ALIAS = Pattern.compile("\\bAS\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** Each rollup target's live column names, for the repair planner. */
    private Map<String, Set<String>> readRollupColumnNames() throws Exception {
        final Map<String, Set<String>> names = new LinkedHashMap<>();
        readRollupColumns().forEach((table, columns) -> names.put(table, columns.keySet()));
        return names;
    }

    /** Each rollup target's live sorting key, for those the connecting user can see. */
    private Map<String, String> readRollupSortKeys() throws Exception {
        final Map<String, String> keys = new LinkedHashMap<>();
        try (var records = this.client.queryRecords(
                "SELECT name, sorting_key FROM system.tables WHERE database = "
                        + quote(this.config.getDatabase())).get()) {
            records.forEach(record -> keys.put(record.getString("name"), record.getString("sorting_key")));
        }
        keys.keySet().retainAll(FlowsSchema.rollupTableNames());
        return keys;
    }

    /**
     * Compare every rollup's live shape against this version's and act on the result (#470).
     *
     * <p><b>Why this warns rather than fails, when a stale {@code flows} table throws.</b> A stale
     * {@code flows} table fails every INSERT, so failing fast is the only honest option. A stale
     * rollup does not touch ingestion; only long-range queries are affected, and they are answered
     * correctly from raw {@code flows} in the meantime. Flows not collected are gone permanently, a
     * rollup not aggregated is repairable for as long as the raw data lives. Failing the collector
     * here would destroy the irreplaceable thing to protect the replaceable one.</p>
     *
     * <p>A failure to <em>read</em> the live state is likewise not fatal, and is logged rather than
     * swallowed: a deployment whose role cannot reach {@code system.tables} at all is exactly the
     * case this check must not turn into an outage.</p>
     */
    private void verifyRollupShapes(final Set<String> unrepaired) {
        final List<RollupShapeCheck.Result> results;
        try {
            results = RollupShapeCheck.compare(this.config.getDatabase(), readRollupSelects(), readRollupColumns());
        } catch (final InterruptedException e) {
            // Startup is being torn down. Restore the flag and leave the verdict at its default —
            // every rollup usable — rather than judging on a half-read catalog.
            Thread.currentThread().interrupt();
            RollupAvailability.recordDrifted(unrepaired);
            return;
        } catch (final Exception e) {
            // The readers below reach the server through CompletableFuture.get(), whose
            // ExecutionException is CHECKED, and close their Records in a try-with-resources whose
            // close() throws Exception. They declare all of it rather than hiding it behind
            // @SneakyThrows: undeclared, a checked exception slips past any catch written here and
            // fails startup — the exact outage the javadoc above promises this check cannot cause,
            // and invisible to the reader and to SpotBugs alike. A collector that cannot read
            // system.tables must still collect.
            log.warn("Could not verify rollup shapes in database '{}': {}. Ingestion is unaffected and"
                    + " queries continue to use the rollups.", this.config.getDatabase(), e.getMessage());
            RollupAvailability.recordDrifted(unrepaired);
            return;
        }

        // Seeded with what the repair could not fix, so a rollup already known bad this start is
        // declined whatever the shape check makes of it. A Set because the two sources overlap:
        // a failed target ALTER is usually also detected as drift a moment later.
        final Set<String> drifted = new LinkedHashSet<>(unrepaired);
        for (final RollupShapeCheck.Result result : results) {
            switch (result.status()) {
                case DRIFTED -> {
                    drifted.add(result.rollup());
                    log.warn("Rollup {} does not match this version's schema: {}. Ingestion is"
                            + " unaffected; long-range queries will fall back to raw flows and be"
                            + " slower until it is repaired.", result.rollup(), result.detail());
                }
                case UNREACHABLE -> {
                    drifted.add(result.rollup());
                    log.warn("Rollup {} cannot be reached: {}.", result.rollup(), result.detail());
                }
                case UNVERIFIABLE -> log.warn("Rollup {} could not be verified: {}. It is still used"
                        + " for queries — an unverified rollup is not a known-bad one.",
                        result.rollup(), result.detail());
                case MATCHES -> log.debug("Rollup {} matches this version's schema.", result.rollup());
            }
        }
        RollupAvailability.recordDrifted(drifted);
    }

    /**
     * Each {@code <rollup>_mv}'s stored SELECT, for those the connecting user can see.
     *
     * <p>A rollup absent from the result is <em>not visible</em>, which ClickHouse does not
     * distinguish from absent: it filters {@code system.tables} by access rather than refusing the
     * query, so a role without a grant on the view gets zero rows and no error. Telling those two
     * apart is {@link RollupShapeCheck}'s job, and it needs the absence rather than an exception.</p>
     */
    private Map<String, String> readRollupSelects() throws Exception {
        final Map<String, String> selects = new LinkedHashMap<>();
        try (var records = this.client.queryRecords(
                "SELECT name, as_select FROM system.tables WHERE database = "
                        + quote(this.config.getDatabase()) + " AND engine = 'MaterializedView'").get()) {
            records.forEach(record -> selects.put(record.getString("name"), record.getString("as_select")));
        }
        return selects;
    }

    /**
     * Each rollup target's columns and their types, for those the connecting user can see.
     *
     * <p>Types as well as names: a dimension whose width changed keeps its name, so a name-only
     * comparison would pass it clean while the column silently truncates.</p>
     */
    private Map<String, Map<String, String>> readRollupColumns() throws Exception {
        final Map<String, Map<String, String>> columns = new LinkedHashMap<>();
        try (var records = this.client.queryRecords(
                "SELECT table, name, type FROM system.columns WHERE database = "
                        + quote(this.config.getDatabase())).get()) {
            records.forEach(record -> columns
                    .computeIfAbsent(record.getString("table"), table -> new LinkedHashMap<>())
                    .put(record.getString("name"), record.getString("type")));
        }
        columns.keySet().retainAll(FlowsSchema.rollupTableNames());
        return columns;
    }

    /**
     * The database name as a SQL string literal. {@link FlowsSchema} already rejects any name
     * outside {@code [A-Za-z0-9_-]+} before it reaches a statement, so this is defence in depth
     * rather than the only guard.
     */
    private static String quote(final String value) {
        return "'" + value.replace("'", "''") + "'";
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

        // The four Enum8 columns below are mapped by name rather than by ordinal. The values are
        // fixed by the schema (FlowsSchema.createFlowsTable) and are already written into every
        // stored row, so they cannot move: with ordinal arithmetic, reordering a constant or
        // inserting one in the middle would silently re-map live data and every historical row
        // would read back as the wrong value. An exhaustive switch instead fails to compile when a
        // constant is added, which is the moment the schema needs the matching ALTER.

        protected byte direction(final Flow.Direction value) {
            return switch (value) {
                case INGRESS -> (byte) 1;
                case EGRESS -> (byte) 2;
                case UNKNOWN -> (byte) 3;
            };
        }

        protected byte samplingAlgorithm(final Flow.SamplingAlgorithm value) {
            return switch (value) {
                case Unassigned -> (byte) 1;
                case SystematicCountBasedSampling -> (byte) 2;
                case SystematicTimeBasedSampling -> (byte) 3;
                case RandomNOutOfNSampling -> (byte) 4;
                case UniformProbabilisticSampling -> (byte) 5;
                case PropertyMatchFiltering -> (byte) 6;
                case HashBasedFiltering -> (byte) 7;
                case FlowStateDependentIntermediateFlowSelectionProcess -> (byte) 8;
            };
        }

        protected byte protocol(final Flow.FlowProtocol value) {
            return switch (value) {
                case NetflowV5 -> (byte) 1;
                case NetflowV9 -> (byte) 2;
                case IPFIX -> (byte) 3;
                case SFLOW -> (byte) 4;
            };
        }

        protected byte locality(final Flow.Locality value) {
            return switch (value) {
                case PUBLIC -> (byte) 1;
                case PRIVATE -> (byte) 2;
            };
        }

        /**
         * A {@code LowCardinality(String)}, not an {@code Enum8} like the four above: the rung set
         * is riptide's own and still growing, and the additive-column path can only add a column,
         * never modify one, so an enum that later gained a value would need machinery that does
         * not exist. The token comes off the constant rather than {@code name()} so renaming a
         * Java constant cannot silently change what stored rows say.
         */
        protected String samplingProvenance(final Flow.SamplingProvenance value) {
            return value.token();
        }
    }
}
