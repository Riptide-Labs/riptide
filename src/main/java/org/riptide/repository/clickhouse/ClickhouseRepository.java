/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ServerException;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        } else {
            // Sent explicitly, because "off" cannot be expressed by silence (#664). ClickHouse
            // 26.7 defaults async_insert to 1, so omitting the setting left coalescing ON with the
            // server's own wait_for_async_insert=1 — a third behaviour neither branch of
            // ClickhouseConfig#asyncInserts describes, and not the direct insert "off" is
            // documented to mean. Pinned by ClickhouseRepositoryIT's #664 probes.
            //
            // Rejections surfaced either way, because the server's default wait is 1, so this is
            // not a hole in the CHECK-barrier contract. What differed is coalescing: a refused
            // insert is atomic on the buffered path but can leave whole blocks committed on a
            // direct one once a batch exceeds max_insert_block_size, which is the ground #548's
            // design stands on.
            builder.serverSetting("async_insert", "0");
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

    /** ClickHouse's own name for the row boundary an insert is split on. */
    private static final String INSERT_BLOCK_SIZE_SETTING = "max_insert_block_size";

    /**
     * Refuse a configured batch that would split into more than one insert block (#700).
     *
     * <p>Measured on the pinned image by {@code MultiBlockPoisonProbeIT}: when a refused insert
     * spans more than one block, the blocks the server already accepted <em>stay committed</em>.
     * One bad row then becomes a silent partial write — {@code BatchingFlowRepository.flush} drops
     * the batch and counts every row as lost, while a prefix persisted and the rollups are left
     * disagreeing with the base table and with each other. Below the boundary a refusal is atomic,
     * which is what {@code PoisonBatchProbeIT} measures and what #548's design rests on.</p>
     *
     * <p>The boundary is read from the server, not assumed: {@code max_insert_block_size} is a
     * server-side setting an operator can profile per user, so a constant here would be right only
     * by luck. At the shipped defaults — 10,000 rows against 1,048,576 — this check never fires;
     * it exists because {@code max-rows} is operator-settable and {@code BatchConfig.validate()}
     * bounds it only at {@code > 0}.</p>
     *
     * <p>Two limits worth naming. It is skipped on the coalesced path, where a refused insert is
     * atomic regardless of size, and when batching is off, where {@code maxRows} governs nothing.
     * And it bounds the <em>configured</em> batch, not an arbitrary {@code persist} call: a caller
     * handing this repository a larger list directly still spans blocks. The only production caller
     * that batches is {@code BatchingFlowRepository}, which never exceeds {@code maxRows}.</p>
     */
    private void checkBatchFitsOneInsertBlock() {
        if (!this.config.getBatch().isEnabled() || this.config.isAsyncInserts()) {
            return;
        }
        final long blockRows = readInsertBlockSize();
        final int maxRows = this.config.getBatch().getMaxRows();
        if (maxRows >= blockRows) {
            throw new IllegalStateException(
                    "riptide.clickhouse.batch.max-rows is " + maxRows + ", which is not below the "
                            + "server's " + INSERT_BLOCK_SIZE_SETTING + " of " + blockRows + ". A batch "
                            + "that spans more than one insert block is not refused atomically: a "
                            + "single rejected row leaves the earlier blocks committed, so part of "
                            + "the batch persists while the drop counter reports all of it lost. "
                            + "Lower max-rows below " + blockRows + ", or raise "
                            + INSERT_BLOCK_SIZE_SETTING + " on the server.");
        }
    }

    /** The effective {@link #INSERT_BLOCK_SIZE_SETTING} for the user riptide connects as. */
    @SneakyThrows
    private long readInsertBlockSize() {
        final String query = "SELECT getSetting('" + INSERT_BLOCK_SIZE_SETTING + "') AS v";
        try (var rows = this.client.queryRecords(query).get()) {
            for (final var row : rows) {
                return Long.parseLong(row.getString("v"));
            }
        }
        throw new IllegalStateException(
                "could not read " + INSERT_BLOCK_SIZE_SETTING + " from the server: " + query
                        + " returned no rows, so the batch size cannot be checked against it");
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

        // After the schema block, not before it: the main client pins the database via
        // setDefaultDatabase, so ANY query issued ahead of ensureDatabase() fails with
        // UNKNOWN_DATABASE on a fresh server — the same trap the manage-mode comment above names
        // for DDL. Being after the DDL costs nothing that matters: what this guard protects is the
        // first insert, and no row has been written yet.
        checkBatchFitsOneInsertBlock();

        // Rollups whose repair did not complete on this start. Every statement below is guarded, so
        // one rollup's failure cannot stop the others or stop ingestion — but a rollup left
        // half-repaired must not keep answering queries either, and the shape check alone will not
        // always catch it. Before #587 a target created current with no view read as UNVERIFIABLE
        // and was deliberately NOT declined; the shape check now asks the server and declines it as
        // NO_VIEW. The seed still covers what the check cannot classify — a DDL failure on a rollup
        // whose view exists but is invisible probes as UNGRANTED and stays UNVERIFIABLE. What this
        // start could not fix, it declines.
        final Set<String> unrepaired = new LinkedHashSet<>();
        // Kept apart from `unrepaired` because the two clear differently. A statement that failed
        // may have been a no-op on a healthy rollup, so a clean shape verdict overrides it. A
        // REFUSED rollup is refused precisely because its shape is one this version cannot reach,
        // and they clear differently. A statement that failed may have been a no-op on a healthy
        // rollup, so a clean shape verdict overrides it; a REFUSED rollup is refused because its
        // shape is one this version cannot reach in place, which a later clean verdict must not
        // erase. The shape check does compare columns, types, the sorting key and the view's
        // SELECT — an earlier version of this comment said it never compares the key, which was
        // false and contradicted the comment on drifted.addAll below.
        final Set<String> refused = new LinkedHashSet<>();
        // The rollups a repair was actually planned for. Without it no line here can say anything
        // true about ONE rollup, only about the start — and a per-rollup claim built from
        // start-wide state is the category error this whole approach exists to avoid.
        final Set<String> plannedRepairs = new LinkedHashSet<>();
        // Set inside the manage block once the catalog read is known to have succeeded. Validate
        // mode never enters it, and NOT_MANAGED is the true statement there: no start repairs.
        RepairPosture posture = RepairPosture.NOT_MANAGED;

        if (this.config.isManageSchema()) {
            // Rollups come after the flows check, not with the DDL above: their materialized views
            // select from flows, so creating them against a stale or mis-provisioned table would
            // fail with an error about the view rather than about the real problem. Targets before
            // views — a view cannot be created before the table its TO clause names.
            //
            // Guarded like the three steps below it. This one is a CREATE ... IF NOT EXISTS that
            // no-ops on every start after the first, which is exactly why leaving it bare was easy
            // to overlook — but a missing CREATE TABLE grant, a disk or quota failure, or a
            // replicated-DDL timeout all reach it, and unguarded any of them stops the collector
            // over a rollup that raw flows can answer for.
            for (final Map.Entry<String, String> table : FlowsSchema.createRollupTablesByRollup(
                    this.config.getDatabase()).entrySet()) {
                repair(unrepaired, table.getKey(), table.getValue(), "target table could not be created");
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
            final Optional<List<String>> targets = planTargetRepair(refused);
            // Interrupt included deliberately: planTargetRepair returns empty for it too, and the
            // sentence says only that no repair was planned, which is true either way. What it must
            // not do is name a cause, so it does not.
            posture = postureOf(targets.isPresent(), true, plannedRepairs.isEmpty());
            plannedRepairs.addAll(targets.orElseGet(List::of));
            for (final String rollup : targets.orElseGet(List::of)) {
                repair(unrepaired, rollup, alters.get(rollup),
                        "target could not be brought up to date");
            }
            // A start that could not read the catalog repairs nothing at all — not the targets it
            // does not know about, and not the views whose targets it does not know about either.
            // Read off the posture rather than re-deriving targets.isPresent(): one condition, one
            // place, which is the rule this change's own javadoc argues for.
            final boolean catalogRead = posture == RepairPosture.PLANNED;

            // Per rollup, and tolerant, because the SELECT now names a column an unrepaired target
            // can lack. CREATE MATERIALIZED VIEW IF NOT EXISTS validates its SELECT even when it
            // no-ops, so a rollup the planner REFUSED — or one whose repair was deferred by a
            // failed catalog read — would otherwise throw here and take ingestion down for a
            // rollup-only concern. That is the outage verifyRollupShapes exists to avoid, and
            // before the rate was appended it could not happen: no rollup SELECT named a column an
            // unrepaired target could be missing.
            final ViewCreation views = planViewCreation(catalogRead, refused,
                    FlowsSchema.rollupTableNames());
            unrepaired.addAll(views.decline());
            final Map<String, String> creates =
                    FlowsSchema.createRollupViewsByRollup(this.config.getDatabase());
            for (final String rollup : views.create()) {
                repair(unrepaired, rollup, creates.get(rollup),
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
            // Recorded as planned, like the target repairs above. Not for the failure case —
            // `unrepaired` is read before the plan, so a failed view repair reports as attempted
            // either way — but for the one that survives success: a view repair that ran while the
            // rollup still differs for another reason must not be told none was planned for it.
            final Optional<List<String>> viewPlan =
                    catalogRead ? planViewRepair(refused) : Optional.<List<String>>empty();
            posture = postureOf(targets.isPresent(), viewPlan.isPresent(), plannedRepairs.isEmpty());
            final List<String> viewRepairs = viewPlan.orElseGet(List::of);
            plannedRepairs.addAll(viewRepairs);
            for (final String rollup : viewRepairs) {
                if (unrepaired.contains(rollup) || refused.contains(rollup)) {
                    // Its target did not get the column, so re-pointing the view would not fail —
                    // MODIFY QUERY does not validate — it would produce a view aggregating by a
                    // dimension the target discards on every insert. Wasted work at best, and the
                    // "working rollup answering with the wrong grain" modifyRollupViews warns about
                    // at worst.
                    continue;
                }
                repair(unrepaired, rollup, modifies.get(rollup), "materialized view could not be repaired");
            }
        }

        // Both modes: report a rollup whose shape is not what this version intends, and keep the
        // query path off it. Runs last because in manage mode the CREATEs and the repair above are
        // what a fresh or upgraded install's shape comes from. Never fails startup — see
        // verifyRollupShapes.
        verifyRollupShapes(plannedRepairs, unrepaired, refused, posture);

        this.client.register(ClickhouseFlow.class, schema);
    }

    /** Which rollups this start may build a view for, and which it must decline for not trying. */
    record ViewCreation(List<String> create, Set<String> decline) { }

    /**
     * The view-creation policy, extracted so it can be tested: the two states it must get right are
     * both unreachable from an integration test.
     *
     * <p>A REFUSED rollup gets no view because the CREATE would <em>succeed</em> — a target carrying
     * the rate outside its sorting key has every column the SELECT names — and riptide would build
     * the very view the refusal exists to prevent, writing rows that outlive the process that
     * declined it. It needs no extra decline: being refused already declines it.</p>
     *
     * <p>An unread catalog ({@code !planned}) skips every view for the same reason one step further
     * out — a refused target is then indistinguishable from a healthy one — and those skips
     * <b>must</b> be declined. A target whose columns and sorting key are current with no view is
     * declined by the shape check itself since #587, which asks the server rather than reading it as
     * UNVERIFIABLE; before that, skipping silently would have left four empty tables answering every
     * long-range query. Declining here still matters, because on an unread catalog the probe outcome
     * is no more trustworthy than the catalog was. Declining is safe for a healthy deployment
     * because a MATCHES verdict clears it on the same pass.</p>
     *
     * <p>Not reachable from an IT: a refused rollup needs hand-built DDL, and an unread catalog
     * needs a transport failure — {@code system.tables} returns filtered rows rather than an error
     * for an under-privileged user, verified on 26.7.</p>
     */
    static ViewCreation planViewCreation(final boolean planned, final Set<String> refused,
            final Collection<String> rollups) {
        if (!planned) {
            return new ViewCreation(List.of(), Set.copyOf(rollups));
        }
        return new ViewCreation(
                rollups.stream().filter(rollup -> !refused.contains(rollup)).toList(), Set.of());
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
            final String what) throws InterruptedException {
        try {
            this.client.execute(ddl).get();
        } catch (final InterruptedException e) {
            // Startup is being torn down. Restore the flag and RETHROW, like persist() does —
            // swallowing it here would leave every later execute() failing instantly on the
            // already-interrupted thread, silently (this branch does not log), so start() would
            // return "successfully" having declined all four rollups on a collector that is being
            // shut down anyway. An interrupt is not a rollup problem and must not be recorded as
            // one.
            Thread.currentThread().interrupt();
            throw e;
        } catch (final Exception e) {
            // Reports the failure and stops (#654). It used to end "until it is repaired", which no
            // later start delivers when planRollupRepair is not going to plan one. It must not
            // assert the query-path consequence either: this
            // runs before verifyRollupShapes, whose MATCHES branch clears the rollup again (see
            // aRollupThatVerifiesCleanIsNotDeclinedByAFailedNoOp), so a failed no-op named here can
            // still answer queries. What happens to the query path is that check's line to write.
            log.warn("Rollup {}: {}: {}. Ingestion is unaffected.", rollup, what, e.getMessage());
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
    private Optional<List<String>> planTargetRepair(final Set<String> refused) {
        final FlowsSchema.RepairPlan plan;
        try {
            plan = FlowsSchema.planRollupRepair(readRollupSortKeys(), readRollupColumnNames());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (final Exception e) {
            // Optional, because "unreadable" and "nothing to repair" are the same empty list and
            // must not be. Unread, `refused` stays empty too — while planViewRepair does its OWN,
            // independent read that can succeed where this one failed. An empty list would let the
            // view repair re-point a view whose target this start never even inspected, and
            // MODIFY QUERY does not validate, so it would succeed and drop the column on every
            // insert. Nothing is repaired on a start that cannot see what it is repairing.
            log.warn("Could not read the rollup shapes in database '{}': {}. Ingestion is unaffected;"
                    + " no rollup is repaired on this start.",
                    this.config.getDatabase(), e.getMessage());
            return Optional.empty();
        }
        // Refusing the ALTER is only half of it. The view's CREATE ... IF NOT EXISTS can still
        // SUCCEED against a refused target — a target carrying the column outside its sorting key
        // has the column the SELECT names — so nothing else would mark the rollup, and the view
        // repair would then re-point it at a SELECT writing the rate into a non-key numeric column
        // of a SummingMergeTree, where ClickHouse sums the rate itself across merges. A refusal that
        // turns a loud Code 36 into a quietly inflated sum is worse than no refusal.
        plan.refused().forEach((rollup, why) -> {
            refused.add(rollup);
            log.warn("Rollup {} left as it is: {}. It is kept out of the query path.", rollup, why);
        });
        plan.repair().forEach(rollup -> log.info("Rollup {}: appending this version's dimensions in place.", rollup));
        return Optional.of(plan.repair());
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
    private Optional<List<String>> planViewRepair(final Set<String> refused) {
        final Map<String, String> live;
        try {
            live = readRollupSelects();
        } catch (final InterruptedException e) {
            // Same discipline as repair() and verifyRollupShapes(): a shutdown interrupt is not a
            // rollup verdict, and swallowing the flag leaves the thread doing work nobody awaits.
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (final Exception e) {
            log.warn("Could not read the rollup views in database '{}': {}. No view repair is planned"
                    + " on this start.", this.config.getDatabase(), e.getMessage());
            return Optional.empty();
        }
        final FlowsSchema.RepairPlan plan =
                FlowsSchema.planViewRepair(this.config.getDatabase(), live, refused);
        // Added to `refused`, not merely logged. Without this a view-refused rollup falls through
        // to "no repair was planned for it", contradicting the line logged here — the duplicate,
        // contradictory statement the outlook exists to prevent.
        plan.refused().forEach((rollup, why) -> {
            refused.add(rollup);
            log.warn("Rollup {} left as it is: {}. It is kept out of the query path.", rollup, why);
        });
        return Optional.of(plan.repair());
    }

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

    /** The rollups neither half of the repair could vouch for. */
    private static Set<String> union(final Set<String> a, final Set<String> b) {
        final Set<String> both = new LinkedHashSet<>(a);
        both.addAll(b);
        return both;
    }

    /**
     * What a start may say about repairs, from whether each catalog read succeeded and whether the
     * plan came to anything.
     *
     * <p>Both reads matter and either can fail alone, because {@code planTargetRepair} and {@code
     * planViewRepair} read the catalog separately. The third argument is what keeps a failed read
     * from erasing work that already happened: a view read that fails after target repairs were
     * planned and ran must not report {@code CATALOG_UNREADABLE}, which tells the operator no
     * repair was planned for any rollup while those repairs' outcomes sit in the same log.</p>
     */
    static RepairPosture postureOf(final boolean targetsRead, final boolean viewsRead,
            final boolean nothingPlanned) {
        if (targetsRead && viewsRead) {
            return RepairPosture.PLANNED;
        }
        return nothingPlanned ? RepairPosture.CATALOG_UNREADABLE : RepairPosture.PLANNED;
    }

    /**
     * What this start was able to do about repairs, which is what a drift line may say about one.
     *
     * <p>Derived from the start's own state and passed in, never re-derived from the drift shape.
     * Two attempts at #654 enumerated which drift classes are permanent inside the shape check and
     * both were reverted with the set wrong — the second telling operators to drop a view and
     * target, discarding up to a year of aggregates, where a lossless ALTER would have done.</p>
     */
    enum RepairPosture {
        /** Schema management is off, so no repair is ever attempted on any start. */
        NOT_MANAGED,
        /** Manage mode, but this start could not read the shapes, so it planned nothing. */
        CATALOG_UNREADABLE,
        /** Manage mode with the catalog read: whatever was planned, was planned. */
        PLANNED
    }

    /**
     * The sentence a drifted rollup's line ends with, or nothing.
     *
     * <p>Answers only what this start <em>did</em> about this rollup, from the sets it already
     * holds. It names no drift class and no remedy: a refused rollup already got both from
     * {@code planTargetRepair}, and any other remedy would be a promise this line cannot keep,
     * because {@code planRollupRepair} refuses several drift classes and {@code riptide onboard}
     * runs that same planner.</p>
     */
    static String repairOutlook(final String rollup, final Set<String> planned,
            final Set<String> unrepaired, final Set<String> refused, final RepairPosture posture) {
        // Ordered, and the order is load-bearing. CATALOG_UNREADABLE first, because an unread
        // catalog declines every rollup into `unrepaired`, where that set means "not repaired"
        // rather than "tried and failed". Then a real failure, which must be pointed at even for a
        // rollup that was also refused. Only then silence for a refusal already explained.
        if (posture == RepairPosture.CATALOG_UNREADABLE) {
            return " No repair was planned for any rollup on this start.";
        }
        if (posture == RepairPosture.NOT_MANAGED) {
            // No remedy named, deliberately. `riptide onboard` runs the same planner, which refuses
            // a shrunk sorting key, a corrected aggregate, a summed missing measure and a column
            // outside the sorting key, so offering it as the fix is #654's false promise in new
            // words. The docs carry the per-case remedies; this line carries only what happened.
            return " No repair is attempted on any start, because this deployment does not manage"
                    + " the schema.";
        }
        if (unrepaired.contains(rollup)) {
            return " Work on it was attempted on this start and did not succeed; the failure is"
                    + " logged above.";
        }
        if (refused.contains(rollup)) {
            // Already told why, by planTargetRepair or planViewRepair. Only the first of those
            // carries a remedy: planViewRepair's downgrade refusal states a reason and no action,
            // which is a gap in that message rather than something to restate here.
            return "";
        }
        if (planned.contains(rollup)) {
            // Says what ran, and stops. A tail naming what this version does not repair would be a
            // claim about every future start inferred from one, which is #654's defect class.
            return " A repair was planned for it on this start and ran, and it still differs.";
        }
        // No tail, for the same reason: "it will be reported again on every start" would assert
        // what every future start does from one start's outcome, and is false whenever
        // planViewRepair's catalog read failed transiently — the next start then repairs it
        // unattended.
        return " No repair was planned for it on this start.";
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
    private void verifyRollupShapes(final Set<String> planned, final Set<String> unrepaired,
            final Set<String> refused, final RepairPosture posture) {
        final List<RollupShapeCheck.Result> results;
        try {
            final Map<String, String> selects = readRollupSelects();
            // Read once and shared: the probe needs to know which targets are readable, and reading
            // system.columns twice could disagree with itself between the two calls.
            final Map<String, Map<String, String>> columns = readRollupColumns();
            results = RollupShapeCheck.compare(this.config.getDatabase(), selects, columns,
                    readRollupSortKeys(), probeInvisibleViews(selects, columns.keySet()));
        } catch (final InterruptedException e) {
            // Startup is being torn down. Restore the flag and record only what the repair already
            // knew it could not fix, rather than judging anything on a half-read catalog.
            Thread.currentThread().interrupt();
            RollupAvailability.recordDrifted(union(unrepaired, refused));
            return;
        } catch (final Exception e) {
            // The readers below reach the server through CompletableFuture.get(), whose
            // ExecutionException is CHECKED, and close their Records in a try-with-resources whose
            // close() throws Exception. They declare all of it rather than hiding it behind
            // @SneakyThrows: undeclared, a checked exception slips past any catch written here and
            // fails startup — the exact outage the javadoc above promises this check cannot cause,
            // and invisible to the reader and to SpotBugs alike. A collector that cannot read
            // system.tables must still collect.
            log.warn("Could not verify rollup shapes in database '{}': {}. Ingestion is unaffected;"
                    + " queries use every rollup except {}, which this start could not repair or"
                    + " refused.", this.config.getDatabase(), e.getMessage(),
                    union(unrepaired, refused));
            RollupAvailability.recordDrifted(union(unrepaired, refused));
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
                            + " unaffected; long-range queries fall back to raw flows for it and are"
                            + " slower.{}", result.rollup(), result.detail(),
                            repairOutlook(result.rollup(), planned, unrepaired, refused, posture));
                }
                case UNREACHABLE -> {
                    // No outlook, and the reason is `plannedRepairs`, not an oversight. This
                    // rollup's target columns could not be read, so the planner never saw it and
                    // its absence from the planned set says nothing about what this start would
                    // have done. "No repair was planned for it" would be a claim built from a read
                    // that failed.
                    drifted.add(result.rollup());
                    log.warn("Rollup {} cannot be reached: {}.", result.rollup(), result.detail());
                }
                case NO_VIEW -> {
                    // Declined for the opposite reason to UNREACHABLE: this rollup IS readable, and
                    // that is the problem. A query against it succeeds and returns what a table
                    // nothing writes to holds, so the fallback to raw flows is the only answer that
                    // is not silently short (#587).
                    //
                    // No outlook here either, for a sharper reason: the repair for a missing view
                    // is the CREATE loop above, and that loop is deliberately NOT in
                    // `plannedRepairs` — it returns every non-refused rollup and its statement is
                    // IF NOT EXISTS, so adding it would make the set mean "all rollups". So the
                    // planned set cannot answer this rollup's question, and the sentence it would
                    // produce, "No repair was planned for it on this start", is false on a manage-
                    // mode start that issued exactly the CREATE that would fix it.
                    drifted.add(result.rollup());
                    log.warn("Rollup {} has no materialized view writing to it: {}. Ingestion is"
                            + " unaffected; long-range queries fall back to raw flows for it and are"
                            + " slower.", result.rollup(), result.detail());
                }
                case UNVERIFIABLE -> log.warn("Rollup {} could not be verified: {}. It is still used"
                        + " for queries — an unverified rollup is not a known-bad one.",
                        result.rollup(), result.detail());
                case MATCHES -> {
                    // Clears a failed repair. Every DDL above is a CREATE ... IF NOT EXISTS or an
                    // idempotent ALTER, so most of them no-op on a healthy deployment — and a
                    // connection reset on a statement that would have changed nothing must not cost
                    // a correct rollup its place in the query path until the next restart. MATCHES
                    // compares the target's columns AND the view's stored SELECT against this
                    // version, so it is a stronger statement than any inference from which
                    // statement failed.
                    drifted.remove(result.rollup());
                    log.debug("Rollup {} matches this version's schema.", result.rollup());
                }
            }
        }
        // After the MATCHES pass, never before it: a refusal is structural and a clean-looking
        // shape must not clear it.
        //
        // Kept deliberately. The refusals riptide issues are about the sorting key, a missing
        // summed measure, or a view downgrade (#657); the shape check catches the first and third
        // itself, so removing this line breaks no test today and a mutation of it survives. It stays
        // because the refusal reasons and the check are independent things that need not remain
        // congruent: a future refusal that is not
        // key-shaped would otherwise be silently unenforced, which is how this whole class of defect
        // arose in the first place.
        drifted.addAll(refused);
        RollupAvailability.recordDrifted(drifted);
    }

    /**
     * What the server says about each rollup view the catalog could not see (#587).
     *
     * <p>{@code system.tables} is filtered by access rather than refused, so an absent view and an
     * ungranted one are the same zero rows there. A query against the view itself separates them by
     * error code, and the two want opposite responses: an absent view means nothing writes to the
     * rollup, while an ungranted one means the rollup is healthy and only its shape is unverifiable.
     *
     * <p><b>Only the invisible ones are probed.</b> A healthy deployment has every view in
     * {@code selects} and issues no query here at all, so this costs nothing in the case that is
     * not the exception.
     *
     * <p>Throws only on interrupt. This runs inside the startup path that must not turn a rollup
     * concern into an ingestion outage, so a server error or a transport failure becomes
     * {@link RollupShapeCheck.ViewProbe#INCONCLUSIVE}, which decides nothing. An interrupt is the
     * exception: it means teardown, and {@code verifyRollupShapes} already refuses to judge a
     * half-read catalog, so it is propagated there rather than absorbed here.
     */
    private Map<String, RollupShapeCheck.ViewProbe> probeInvisibleViews(final Map<String, String> selects,
            final Set<String> visibleTargets) throws InterruptedException {
        return probeViews(selects, visibleTargets, this.config.getDatabase(), this::probeView);
    }

    /** Issues one probe. Separated so the map-building around it can be tested without a server. */
    @FunctionalInterface
    interface ViewProber {
        RollupShapeCheck.ViewProbe probe(String qualifiedView) throws InterruptedException;
    }

    /**
     * The probe outcomes, keyed the way {@code RollupShapeCheck.compare} looks them up.
     *
     * <p>Extracted because this is where the feature can go silently inert. {@code compare} reads
     * the map by <em>rollup target</em> name while the query needs the <em>qualified view</em>
     * name, and the two differ. Key it by the view name and every lookup misses, every rollup reads
     * INCONCLUSIVE, nothing is ever declined — and no verdict-level assertion notices, because they
     * all hand-build this map rather than producing it.</p>
     */
    static Map<String, RollupShapeCheck.ViewProbe> probeViews(final Map<String, String> selects,
            final Set<String> visibleTargets, final String database, final ViewProber prober)
            throws InterruptedException {
        final Map<String, RollupShapeCheck.ViewProbe> probes = new LinkedHashMap<>();
        for (final String rollup : rollupsNeedingProbe(selects, visibleTargets)) {
            probes.put(rollup, prober.probe(FlowsSchema.qualifiedRollupView(database, rollup)));
        }
        return probes;
    }

    /**
     * Which rollups the catalog could not see a view for, and therefore the only ones worth asking
     * the server about.
     *
     * <p>Extracted so it can be tested, like {@code planViewCreation}: that a healthy deployment
     * issues no probe at all is the property that makes this change free for everyone not in the
     * broken state, and it is not observable from the verdicts.</p>
     */
    static List<String> rollupsNeedingProbe(final Map<String, String> selects,
            final Set<String> visibleTargets) {
        return FlowsSchema.rollupTableNames().stream()
                .filter(rollup -> !selects.containsKey(FlowsSchema.rollupViewName(rollup)))
                // A rollup whose TARGET is unreadable answers UNREACHABLE before the view branch is
                // reached, so its probe outcome is discarded. Asking anyway spends a round trip per
                // rollup on a start that is already degraded, which is when a deployment can least
                // afford it.
                .filter(visibleTargets::contains)
                .toList();
    }

    /**
     * One probe: the server's answer to a trivial query against {@code view}.
     *
     * <p><b>Do not "fix" this by granting the writer SELECT on the view.</b> A maintainer who sees
     * every probe answer UNGRANTED will find that tempting, and it is the one change that must not
     * be made: {@code ProvisioningDdl} withholds SELECT on each {@code _mv} on purpose, because a
     * row policy on the target does not apply to rows read through the view's name, so the grant
     * would hand every tenant's writer a read path around the policy. That reasoning is on the
     * ClickHouse configuration page, under the grants it lists for {@code flow_writer}.
     * The probe works precisely because it is denied — a dropped view answers
     * UNKNOWN_TABLE while an existing one answers ACCESS_DENIED, which is the whole discrimination.
     *
     * <p>The statement and the path are the ones {@code RollupShapeDriftIT} measured the codes on.
     * The client wraps a {@link ServerException} in an {@code ExecutionException}, so the cause
     * chain is walked rather than the top-level type inspected — a check on the thrown type alone
     * finds nothing and would report every state as inconclusive.
     */
    private RollupShapeCheck.ViewProbe probeView(final String view) throws InterruptedException {
        return outcomeOfProbe(view, () -> {
            awaitBounded(this.client.queryRecords(PROBE_STATEMENT + view), PROBE_TIMEOUT, view);
        });
    }

    /**
     * The probe's statement, shared with the test that measured what the server answers to it.
     *
     * <p>Package-private and referenced by {@code RollupShapeDriftIT} rather than re-typed there.
     * The codes this class branches on were measured for <em>this</em> statement; a second copy
     * could drift, and the IT would go on measuring a query production no longer issues while
     * staying green.</p>
     */
    static final String PROBE_STATEMENT = "SELECT count() FROM ";

    /** One probe attempt, which may fail in any of the ways a network call can. */
    @FunctionalInterface
    interface ProbeCall {
        void run() throws Exception;
    }

    /**
     * What one probe attempt means, separated from issuing it so the paths that are not an error
     * code can be tested at all.
     *
     * <p>Three of the four outcomes here never reach {@link #outcomeOf(Throwable)}: a query that
     * succeeds, a timeout, and an interrupt. Without this seam they were reachable only from a
     * server that misbehaves on cue, which is to say untested.</p>
     */
    static RollupShapeCheck.ViewProbe outcomeOfProbe(final String view, final ProbeCall call)
            throws InterruptedException {
        try {
            call.run();
            // Reached when the view is both present and readable, which contradicts the catalog
            // read that sent us here. Nothing measured says what that means, so it decides nothing.
            log.debug("Probe of {} succeeded though the catalog did not list it; not acting on it", view);
            return RollupShapeCheck.ViewProbe.INCONCLUSIVE;
        } catch (final InterruptedException e) {
            // Rethrown, not swallowed. verifyRollupShapes has an InterruptedException arm that
            // refuses to judge anything on a half-read catalog during teardown, and swallowing here
            // would walk past it: every remaining probe would fail instantly and silently, and
            // verdicts would be recorded from a catalog nobody finished reading.
            throw e;
        } catch (final Exception e) {
            if (carriesInterrupt(e)) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("probe of " + view + " interrupted: " + e);
            }
            final RollupShapeCheck.ViewProbe outcome = outcomeOf(e);
            if (outcome == RollupShapeCheck.ViewProbe.INCONCLUSIVE) {
                // The only evidence an operator gets that the discriminator ran at all. Without it,
                // "could not be verified" is indistinguishable from a probe that never fired or a
                // server that stopped separating the codes.
                log.debug("Probe of {} decided nothing: {}", view, e.toString());
            }
            return outcome;
        }
    }

    /**
     * Waits for one probe's result, bounded, closing or cancelling whatever it gets.
     *
     * <p>Extracted so the bound is testable. Inlined, a change from {@code get(timeout)} to a bare
     * {@code get()} restores an unbounded startup wait and no test notices: the ITs run against a
     * container that answers at once, so bounded and unbounded are indistinguishable there.</p>
     */
    static void awaitBounded(final java.util.concurrent.Future<? extends AutoCloseable> pending,
            final Duration timeout, final String view) throws Exception {
        try (var records = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            // Nothing to read: the refusal this probe exists for arrives from get(), which is the
            // path RollupShapeDriftIT measured the codes on. A result that does arrive means the
            // view was readable after all, which decides nothing either way.
            log.trace("Probe of {} returned {}", view, records);
        } catch (final TimeoutException | InterruptedException e) {
            // Both abandon the query, so both must cancel it. Only the timeout did, which left the
            // same leak on the teardown path it was written to prevent on the timeout path.
            pending.cancel(true);
            throw e;
        }
    }

    /**
     * How long one probe may block.
     *
     * <p>Bounded because this runs on the startup path. {@code get()} without a timeout waits
     * forever on a server that accepts the connection and never answers, once per invisible rollup,
     * and ingestion never begins — an outage caused by a rollup-only concern, which is the outcome
     * every guard on this path exists to prevent.</p>
     *
     * <p>Per probe, not in aggregate. Four invisible views against a server that accepts the
     * connection and never answers therefore add four times this before ingestion begins. That is
     * accepted rather than bounded: a total budget would be new untested control flow on the
     * startup path, and the case needs every view invisible AND a server that connects but never
     * replies. Stated here so the number is known rather than discovered.</p>
     *
     * <p>Converted with {@code toMillis()}, not {@code toSeconds()}: a later value below one second
     * would truncate to {@code get(0, SECONDS)}, and every probe would time out immediately and
     * report INCONCLUSIVE with nothing saying why.</p>
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The probe outcome for a thrown failure.
     *
     * <p>The cause chain is walked rather than the thrown type inspected. The client wraps a
     * {@link ServerException} in an {@code ExecutionException}, so a check on the top-level type
     * finds nothing and would report every state as inconclusive — the discriminator would compile,
     * pass a unit test built on the same mistake, and never fire on a real server.</p>
     *
     * <p>A failure carrying no {@code ServerException} at all is a transport problem rather than an
     * answer about the view, and decides nothing.</p>
     */
    static RollupShapeCheck.ViewProbe outcomeOf(final Throwable thrown) {
        // Hand-rolled rather than Guava's getCausalChain, which throws IllegalArgumentException on
        // a self-referential chain. That exception would escape this method, escape probeView's
        // catch, and abort the shape check for every rollup — breaking the "a transport failure
        // becomes INCONCLUSIVE" promise on the one input that is already pathological.
        final Set<Throwable> seen = new LinkedHashSet<>();
        for (Throwable cause = thrown; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ServerException server) {
                return outcomeOf(server.getCode());
            }
        }
        return RollupShapeCheck.ViewProbe.INCONCLUSIVE;
    }

    /**
     * Whether a failure carries an interrupt anywhere in its cause chain.
     *
     * <p>{@code get()} throws {@link InterruptedException} directly when this thread is
     * interrupted, but a client that interrupts its own worker surfaces it wrapped in an
     * {@code ExecutionException}. Unwrapped, teardown walks straight past the arm in
     * {@code verifyRollupShapes} that refuses to judge a half-read catalog.</p>
     */
    static boolean carriesInterrupt(final Throwable thrown) {
        final Set<Throwable> seen = new LinkedHashSet<>();
        for (Throwable cause = thrown; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The probe outcome for one server error code.
     *
     * <p>An if-chain rather than a switch because {@code ErrorCodes} accessors are method calls and
     * cannot be case labels. {@code TABLE_NOT_FOUND} is read from the client so a renumbering stays
     * correct; {@code ACCESS_DENIED} is a literal because client-v2 0.10.0 has no member for it.
     * There is no arm for {@code UNKNOWN_DATABASE} — see {@code RollupShapeCheck.compareOne}, which
     * explains why that case is answered one branch earlier.</p>
     */
    static RollupShapeCheck.ViewProbe outcomeOf(final int code) {
        if (code == ServerException.ErrorCodes.TABLE_NOT_FOUND.getCode()) {
            return RollupShapeCheck.ViewProbe.ABSENT;
        }
        if (code == VIEW_UNGRANTED_CODE) {
            return RollupShapeCheck.ViewProbe.UNGRANTED;
        }
        return RollupShapeCheck.ViewProbe.INCONCLUSIVE;
    }

    /** {@code ACCESS_DENIED}. A literal because client-v2 0.10.0 has no enum member for it. */
    private static final int VIEW_UNGRANTED_CODE = 497;

    /**
     * Each {@code <rollup>_mv}'s stored SELECT, for those the connecting user can see.
     *
     * <p>A rollup absent from the result is <em>not visible</em>, which ClickHouse does not
     * distinguish from absent: it filters {@code system.tables} by access rather than refusing the
     * query, so a role without a grant on the view gets zero rows and no error. This read reports
     * the absence rather than an exception, and {@link #probeInvisibleViews} then asks the server
     * which of the two it is (#587).</p>
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
