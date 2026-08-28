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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
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
        // Kept apart from `unrepaired` because the two clear differently. A statement that failed
        // may have been a no-op on a healthy rollup, so a clean shape verdict overrides it. A
        // REFUSED rollup is refused precisely because its shape is one this version cannot reach,
        // and the shape check cannot see the difference — it compares columns and the view's SELECT,
        // never the sorting key, which is the thing a refusal is usually about.
        final Set<String> refused = new LinkedHashSet<>();

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
            for (final String rollup : targets.orElseGet(List::of)) {
                repair(unrepaired, rollup, alters.get(rollup),
                        "target could not be brought up to date");
            }
            // A start that could not read the catalog repairs nothing at all — not the targets it
            // does not know about, and not the views whose targets it does not know about either.
            final boolean planned = targets.isPresent();

            // Per rollup, and tolerant, because the SELECT now names a column an unrepaired target
            // can lack. CREATE MATERIALIZED VIEW IF NOT EXISTS validates its SELECT even when it
            // no-ops, so a rollup the planner REFUSED — or one whose repair was deferred by a
            // failed catalog read — would otherwise throw here and take ingestion down for a
            // rollup-only concern. That is the outage verifyRollupShapes exists to avoid, and
            // before the rate was appended it could not happen: no rollup SELECT named a column an
            // unrepaired target could be missing.
            final ViewCreation views = planViewCreation(planned, refused,
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
            for (final String rollup : planned ? planViewRepair(refused) : List.<String>of()) {
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
        verifyRollupShapes(unrepaired, refused);

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
     * <b>must</b> be declined. A target whose columns and sorting key are current with no view reads
     * as UNVERIFIABLE, which is deliberately not declined, so skipping silently would leave four
     * empty tables answering every long-range query. Declining is safe for a healthy deployment
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
    private List<String> planViewRepair(final Set<String> refused) {
        final Map<String, String> live;
        try {
            live = readRollupSelects();
        } catch (final InterruptedException e) {
            // Same discipline as repair() and verifyRollupShapes(): a shutdown interrupt is not a
            // rollup verdict, and swallowing the flag leaves the thread doing work nobody awaits.
            Thread.currentThread().interrupt();
            return List.of();
        } catch (final Exception e) {
            log.warn("Could not read the rollup views in database '{}': {}. No view repair is planned"
                    + " on this start.", this.config.getDatabase(), e.getMessage());
            return List.of();
        }
        final FlowsSchema.RepairPlan plan =
                FlowsSchema.planViewRepair(this.config.getDatabase(), live, refused);
        plan.refused().forEach((rollup, why) ->
                log.warn("Rollup {} left as it is: {}. It is kept out of the query path.", rollup, why));
        return plan.repair();
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
    private void verifyRollupShapes(final Set<String> unrepaired, final Set<String> refused) {
        final List<RollupShapeCheck.Result> results;
        try {
            results = RollupShapeCheck.compare(this.config.getDatabase(), readRollupSelects(),
                    readRollupColumns(), readRollupSortKeys());
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
                    // Makes no claim about whether a repair is coming, because this line cannot
                    // know (#654): the decision belongs to FlowsSchema.planRollupRepair and
                    // planViewRepair, and it logs the repairs it does plan. Deliberately not
                    // restated here — two reverted attempts at #654 re-derived which drift is
                    // permanent in a third place and got the set wrong both times.
                    log.warn("Rollup {} does not match this version's schema: {}. Ingestion is"
                            + " unaffected; long-range queries fall back to raw flows for it and are"
                            + " slower.", result.rollup(), result.detail());
                }
                case UNREACHABLE -> {
                    drifted.add(result.rollup());
                    log.warn("Rollup {} cannot be reached: {}.", result.rollup(), result.detail());
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
        // Redundant today, and kept deliberately. Every refusal riptide currently issues is about
        // the sorting key, which the shape check now compares itself — so removing this line breaks
        // no test, and a mutation of it survives. It stays because the refusal reasons and the check
        // are independent things that need not remain congruent: a future refusal that is not
        // key-shaped would otherwise be silently unenforced, which is how this whole class of defect
        // arose in the first place.
        drifted.addAll(refused);
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
