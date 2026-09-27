/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.riptide.secrets.SecretResolvers;
import org.riptide.snmp.collect.CollectedTable;
import org.riptide.snmp.collect.CollectionDefinition;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableListener;
import org.snmp4j.util.TableUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SnmpUtils {

    // IF-MIB (RFC 2863): ifTable ifDescr; ifXTable ifName / ifHighSpeed (Mbit/s) / ifAlias
    private static final OID IF_DESCR = new OID("1.3.6.1.2.1.2.2.1.2");
    private static final OID IFX_NAME = new OID("1.3.6.1.2.1.31.1.1.1.1");
    private static final OID IFX_HIGH_SPEED = new OID("1.3.6.1.2.1.31.1.1.1.15");
    private static final OID IFX_ALIAS = new OID("1.3.6.1.2.1.31.1.1.1.18");

    /**
     * Upper bound on one whole {@link #getIfInfoMapAsync} call, including the ifTable fallback
     * walk. snmp4j's timeout and retries bound each <em>round-trip</em>; nothing in snmp4j
     * bounds how many round-trips a table walk takes, so walk duration is agent-paced
     * (#536).
     *
     * <p>A constant, deliberately not derived from the endpoint's cadence. The first
     * version used {@code min(refresh-interval, ceiling)}, and review produced the
     * counterexample: a 5,000-interface device at WAN latency needs ~50 s of sequential
     * round-trips, and under a 30 s profile every walk was abandoned forever — a device
     * the unbounded code enriched, starved by the bound. Two minutes admits every
     * real-sized table at real latencies (walks do not overlap regardless: the in-flight
     * flag serialises them per endpoint), while still bounding a hostile agent to two
     * minutes of one poller permit per back-off cycle.</p>
     */
    static final Duration WALK_BUDGET = Duration.ofMinutes(2);

    /**
     * Upper bound on collected rows. This exists because the row count is agent-controlled:
     * an agent serving a strictly increasing, never-ending table (each response inside the
     * per-request timeout) would otherwise walk forever while the collected list grows
     * without bound. snmp4j already rejects non-increasing OIDs; this closes the
     * increasing-forever case; {@code TableUtils.setRowLimit} gets the cap plus one so the
     * library stops issuing requests one row past it rather than relying on the listener's
     * refusal alone. The bound is exclusive: a table completing at exactly this many rows
     * is a clean walk (the first version capped on the cap-th row itself, before the clean
     * {@code finished()} could arrive, abandoning a complete boundary table forever).
     *
     * <p>Known limitation, documented rather than hidden: subscriber-facing gear (BNG/BRAS)
     * can legitimately exceed this many ifTable entries; such a device is not enrichable
     * and its walks land on the abandoned outcome. Raising the cap is a memory trade
     * (collected rows are buffered before the poller snapshots them, times the poller's
     * permit count in the adversarial case) and should happen against a real request, not speculation.</p>
     */
    static final int MAX_TABLE_ROWS = 65_536;

    private SnmpUtils() {
    }

    enum WalkOutcome {
        OK, TIMEOUT, ERROR,
        /**
         * The walk was stopped at our bounds (wall-clock budget or row cap), not by the
         * agent failing to answer. Kept distinct from {@link #TIMEOUT} so the meters and
         * logs cannot claim an agent "did not answer" when it answered too much.
         */
        ABANDONED
    }

    public record WalkResult(Map<Integer, IfInfo> rows, WalkOutcome outcome) {
    }

    /** One table walk: index to the columns' variable bindings, in column order. Null cells kept. */
    record RawTable(Map<Integer, VariableBinding[]> rows, WalkOutcome outcome) {
    }

    /**
     * One table walk that parks no thread. The returned future completes from snmp4j's callback
     * when the walk finishes, or from {@code timer} when the deadline passes first, whichever
     * reaches the collector first. The loser completes nothing: a late {@code finished} after an
     * abandonment is refused by the collector, and a deadline after a finish is cancelled.
     */
    static CompletableFuture<RawTable> walkRawAsync(final Snmp snmp, final Target<?> target, final SnmpEndpoint endpoint,
                                                    final OID[] columns, final int maxRowsPerPdu,
                                                    final long deadlineNanos, final ScheduledExecutorService timer) {
        final long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            // checked before the request reaches the wire: the fallback walk after a slow
            // ifXTable walk would otherwise fire a real GETBULK, abandon it instantly, and
            // leave snmp4j delivering into a closing session
            return CompletableFuture.completedFuture(new RawTable(new TreeMap<>(), WalkOutcome.ABANDONED));
        }
        final TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
        // belt and braces with the collector's own cap: the library stops issuing requests
        // at the same boundary instead of relying on the listener's refusal alone. Plus
        // one because the cap is exclusive. The collector must see a cap-exceeding row
        // to distinguish "more than the cap" from "complete at exactly the cap"
        tableUtils.setRowLimit(MAX_TABLE_ROWS + 1);
        tableUtils.setMaxNumRowsPerPDU(maxRowsPerPdu);
        tableUtils.setMaxNumColumnsPerPDU(columns.length);
        final CompletableFuture<RawTable> future = new CompletableFuture<>();
        // the listener variant, not the synchronous one: with getTable(target, columns,
        // lower, upper) the blocking wait belongs to snmp4j and the loop termination
        // belongs to the AGENT, so walk duration and heap were both agent-controlled. Here
        // the deadline is ours, and nothing waits on it
        final WalkCollector collector = new WalkCollector(MAX_TABLE_ROWS,
                done -> future.complete(toRawTable(done, columns, endpoint)));
        final ScheduledFuture<?> deadline;
        try {
            deadline = timer.schedule(() -> {
                if (collector.abandon()) {
                    // rate-bounded by the poller's back-off, which the failed outcome engages
                    log.warn("SNMP walk of {} exceeded its budget and was abandoned; rows collected so far "
                            + "are discarded (an incomplete table must not be cached as complete)", endpoint);
                    future.complete(new RawTable(new TreeMap<>(), WalkOutcome.ABANDONED));
                }
            }, remainingNanos, TimeUnit.NANOSECONDS);
        } catch (final RejectedExecutionException e) {
            // the SNMP service is closing: nothing could bound this walk, so it never starts
            return CompletableFuture.completedFuture(new RawTable(new TreeMap<>(), WalkOutcome.ABANDONED));
        }
        final CompletableFuture<RawTable> walk = future.whenComplete((table, failure) -> deadline.cancel(false));
        tableUtils.getTable(target, columns, collector, null, null, null);
        return walk;
    }

    /** Turns a finished collector's events into a table. Rows are all or nothing. */
    private static RawTable toRawTable(final WalkCollector collector, final OID[] columns, final SnmpEndpoint endpoint) {
        if (collector.capped()) {
            log.warn("SNMP walk of {} stopped at the {}-row cap and was abandoned: either the "
                    + "table keeps growing (no real access device does this) or the device carries "
                    + "more rows than riptide collects. Rows are discarded and the endpoint "
                    + "backs off", endpoint, MAX_TABLE_ROWS);
            return new RawTable(new TreeMap<>(), WalkOutcome.ABANDONED);
        }

        final Map<Integer, VariableBinding[]> rows = new TreeMap<>();

        for (final TableEvent tableEvent : collector.events()) {
            if (tableEvent.isError()) {
                // The SNMP4J target must not be logged: its toString() carries the credential (#335)
                log.warn("Error querying {} for {}: {}", columns[0], endpoint, tableEvent.getErrorMessage());
                // rows collected before the error are discarded: an incomplete table must not
                // be cached as if it were complete
                final var outcome = tableEvent.getStatus() == TableEvent.STATUS_TIMEOUT
                        ? WalkOutcome.TIMEOUT
                        : WalkOutcome.ERROR;
                return new RawTable(new TreeMap<>(), outcome);
            }
            if (tableEvent.getIndex() == null || tableEvent.getColumns() == null) {
                continue;
            }

            rows.put(tableEvent.getIndex().last(), tableEvent.getColumns());
        }

        return new RawTable(rows, WalkOutcome.OK);
    }

    private static CompletableFuture<WalkResult> walkColumnsAsync(final Snmp snmp, final Target<?> target,
                                                                  final SnmpEndpoint snmpEndpoint, final OID[] columns,
                                                                  final Function<List<VariableBinding>, IfInfo> row,
                                                                  final long deadlineNanos,
                                                                  final ScheduledExecutorService timer) {
        // ten rows per PDU is TableUtils's own default; passed explicitly here now that
        // walkRawAsync takes the cap as a parameter, so enrichment behaviour is unchanged
        return walkRawAsync(snmp, target, snmpEndpoint, columns, 10, deadlineNanos, timer)
                .thenApply(raw -> toWalkResult(raw, row));
    }

    private static WalkResult toWalkResult(final RawTable raw, final Function<List<VariableBinding>, IfInfo> row) {
        if (raw.outcome() != WalkOutcome.OK) {
            return new WalkResult(new TreeMap<>(), raw.outcome());
        }

        final Map<Integer, IfInfo> interfaces = new TreeMap<>();
        for (final Map.Entry<Integer, VariableBinding[]> entry : raw.rows().entrySet()) {
            // Arrays.asList, not List.of: sparse tables leave null entries for missing columns
            final IfInfo ifInfo = row.apply(Arrays.asList(entry.getValue()));
            if (ifInfo != null) {
                interfaces.put(entry.getKey(), ifInfo);
            }
        }
        return new WalkResult(interfaces, WalkOutcome.OK);
    }

    /**
     * Walks every column of {@code definition}, one table walk per distinct table the columns
     * belong to, and joins the results on the row index. ifXTable and ifTable columns in
     * {@link org.riptide.snmp.collect.CollectionDefinitions#IF_MIB_INTERFACES} are two tables;
     * either walk failing fails the whole collect, since a partial table must not be cached as
     * complete. The table walks run one after another, chained on completion, under the one
     * shared deadline: a later walk starts only if every earlier one came back clean.
     */
    static CompletableFuture<CollectedTable> collectAsync(final Snmp snmp, final Target<?> target,
                                                          final SnmpEndpoint endpoint,
                                                          final CollectionDefinition definition,
                                                          final long deadlineNanos,
                                                          final ScheduledExecutorService timer) {
        final Map<OID, List<CollectionDefinition.Column>> byTable = new LinkedHashMap<>();
        for (final CollectionDefinition.Column column : definition.columns()) {
            byTable.computeIfAbsent(tableOf(column.oid()), key -> new ArrayList<>()).add(column);
        }
        // written by one walk's completion at a time: each chained walk starts only after the
        // previous stage completed, and that ordering is the happens-before between them
        final Map<Integer, Map<String, String>> info = new TreeMap<>();
        final Map<Integer, Map<String, Long>> values = new TreeMap<>();
        CompletableFuture<Boolean> clean = CompletableFuture.completedFuture(true);
        for (final List<CollectionDefinition.Column> columns : byTable.values()) {
            final OID[] oids = columns.stream().map(CollectionDefinition.Column::oid).toArray(OID[]::new);
            clean = clean.thenCompose(ok -> !ok
                    ? CompletableFuture.completedFuture(false)
                    : walkRawAsync(snmp, target, endpoint, oids, definition.maxRowsPerPdu(), deadlineNanos, timer)
                            .thenApply(raw -> merge(raw, columns, info, values)));
        }
        return clean.thenApply(ok -> {
            if (!ok) {
                return new CollectedTable(Map.of(), true);
            }
            final Map<Integer, CollectedTable.CollectedRow> rows = new TreeMap<>();
            for (final Integer index : union(info.keySet(), values.keySet())) {
                rows.put(index, new CollectedTable.CollectedRow(
                        info.getOrDefault(index, Map.of()), values.getOrDefault(index, Map.of())));
            }
            return new CollectedTable(rows, false);
        });
    }

    /** Folds one table walk into the joined maps. False when the walk did not come back clean. */
    private static boolean merge(final RawTable raw, final List<CollectionDefinition.Column> columns,
                                 final Map<Integer, Map<String, String>> info,
                                 final Map<Integer, Map<String, Long>> values) {
        if (raw.outcome() != WalkOutcome.OK) {
            return false;
        }
        for (final Map.Entry<Integer, VariableBinding[]> row : raw.rows().entrySet()) {
            for (int i = 0; i < columns.size(); i++) {
                final VariableBinding cell = row.getValue()[i];
                final CollectionDefinition.Column column = columns.get(i);
                if (column.type() == CollectionDefinition.ColumnType.INFO) {
                    final String text = string(cell);
                    if (text != null) {
                        info.computeIfAbsent(row.getKey(), k -> new HashMap<>()).put(column.metric(), text);
                    }
                } else {
                    final Long number = number(cell);
                    if (number != null) {
                        values.computeIfAbsent(row.getKey(), k -> new HashMap<>()).put(column.metric(), number);
                    }
                }
            }
        }
        return true;
    }

    /** The table entry OID is the column OID without its last sub-identifier. */
    private static OID tableOf(final OID column) {
        return new OID(column.getValue(), 0, column.size() - 1);
    }

    private static Set<Integer> union(final Set<Integer> left, final Set<Integer> right) {
        final Set<Integer> result = new TreeSet<>(left);
        result.addAll(right);
        return result;
    }

    /**
     * Collects row events and owns the row bound. Thread contract: snmp4j delivers
     * {@code next}/{@code finished} on its dispatcher thread, and the deadline timer calls
     * {@link #abandon}. The monitor decides which of them finishes the walk, exactly once; the
     * {@code onDone} callback runs only for a walk the agent finished, outside the monitor, so
     * whatever it completes never runs while holding it.
     */
    static final class WalkCollector implements TableListener {

        private final List<TableEvent> events = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final int maxRows;
        private final Consumer<WalkCollector> onDone;
        private boolean finished;
        private boolean capped;

        WalkCollector(final int maxRows) {
            this(maxRows, collector -> { });
        }

        WalkCollector(final int maxRows, final Consumer<WalkCollector> onDone) {
            this.maxRows = maxRows;
            this.onDone = onDone;
        }

        @Override
        public boolean next(final TableEvent event) {
            final boolean more;
            synchronized (this) {
                if (this.finished) {
                    return false;
                }
                this.events.add(event);
                // strictly greater: the maxRows-th row may be the table's last, and its clean
                // finished() must win over a false cap
                more = !event.isError() && this.events.size() <= this.maxRows;
                if (!more) {
                    this.capped = !event.isError();
                    this.finished = true;
                }
            }
            if (!more) {
                done();
            }
            return more;
        }

        @Override
        public void finished(final TableEvent event) {
            synchronized (this) {
                if (this.finished) {
                    return;
                }
                if (event != null && (event.isError() || event.getIndex() != null)) {
                    this.events.add(event);
                }
                this.finished = true;
            }
            done();
        }

        private void done() {
            this.done.countDown();
            this.onDone.accept(this);
        }

        @Override
        public synchronized boolean isFinished() {
            return this.finished;
        }

        /**
         * The blocking view of the same completion, for callers that drive the collector
         * directly (the walk itself never waits on it). Waits up to the remaining budget and
         * returns false on expiry, after which late deliveries are dropped ({@code next}
         * answers false).
         */
        boolean await(final long remainingNanos) {
            if (remainingNanos <= 0) {
                abandon();
                return false;
            }
            try {
                if (this.done.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                    return true;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            abandon();
            return false;
        }

        /**
         * Stops the walk at our deadline. Returns whether this call finished it: false means the
         * agent's own {@code finished} (or the row cap) got there first and has already
         * completed the walk, so the caller must complete nothing.
         *
         * <p>What actually stops the round-trips after an abandonment depends on who owns the
         * session. {@code getIfInfoMapAsync} opens a session per walk and closes it once the walk
         * completes, which cancels the pending request and delivers a final refused event.
         * {@code collectAsync} runs on a shared, never-closed session instead: nothing cancels the
         * in-flight GETBULK, so it runs to its own timeout-times-retries and snmp4j delivers the
         * response (or a timeout) to {@code next()} in due course, which by then answers
         * {@code false} because {@code isFinished()} is already {@code true}. TableUtils never
         * consults {@code isFinished()} on its own; the refusing {@code next} is what stops a
         * stale response from reopening this collector, whichever path produced it.</p>
         */
        synchronized boolean abandon() {
            if (this.finished) {
                return false;
            }
            this.finished = true;
            return true;
        }

        synchronized boolean capped() {
            return this.capped;
        }

        synchronized List<TableEvent> events() {
            return List.copyOf(this.events);
        }
    }

    private static IfInfo ifXRow(final List<VariableBinding> columns) {
        final String name = string(columns.get(0));
        if (name == null) {
            return null;
        }
        return new IfInfo(name, string(columns.get(2)), number(columns.get(1)));
    }

    private static IfInfo ifRow(final List<VariableBinding> columns) {
        final String descr = string(columns.get(0));
        return descr != null ? new IfInfo(descr, null, null) : null;
    }

    private static String string(final VariableBinding vb) {
        // isException: noSuchObject/noSuchInstance/endOfMibView must not leak as literal strings
        if (vb == null || vb.getVariable() == null || vb.getVariable().isException() || vb.getVariable().toString().isEmpty()) {
            return null;
        }
        return vb.getVariable().toString();
    }

    /** Package-private, not private: {@code SnmpCollectTest} pins the non-numeric case directly. */
    static Long number(final VariableBinding vb) {
        if (vb == null || vb.getVariable() == null || vb.getVariable().isException()) {
            return null;
        }
        try {
            return vb.getVariable().toLong();
        } catch (final UnsupportedOperationException e) {
            // an agent answering a counter/gauge column with something non-numeric (e.g. an
            // OctetString) must not turn into an exception the poller has to catch: the cell
            // is simply absent, the same as a null or exception value
            return null;
        }
    }

    /**
     * Walks the exporter's interface table: ifXTable (ifName/ifHighSpeed/ifAlias) first,
     * falling back to the legacy ifTable (ifDescr only), unless the first walk timed out.
     *
     * <p>The walk runs on a session of its own, closed once the walk completes and before the
     * returned future does. The close runs on {@code timer}, never on the snmp4j thread that
     * completed the walk: {@code Snmp.close()} joins snmp4j's own threads, and a callback
     * thread joining itself never returns.</p>
     *
     * @param budgetNanos one deadline for the whole call: the fallback walk shares the budget
     *                    rather than doubling it
     */
    static CompletableFuture<WalkResult> getIfInfoMapAsync(final SnmpEndpoint snmpEndpoint,
                                                           final SecretResolvers secretResolvers,
                                                           final long budgetNanos,
                                                           final ScheduledExecutorService timer) throws IOException {
        final SnmpBuilder snmpBuilder = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getSnmpBuilder();
        final long deadlineNanos = System.nanoTime() + budgetNanos;
        // Known limitation, shared with DefaultSnmpService.session(): if snmpBuilder.build()
        // throws here, the socket and two dispatcher threads that its earlier .udp()/.threads(2)
        // calls already created leak, because snmp4j 3.13.1's SnmpBuilder exposes no public way
        // to reach or close that pre-built Snmp (see session()'s javadoc for the full case). Not
        // worked around here for the same reason: no supported extension point offers one.
        final Snmp snmp = snmpBuilder.build();
        final CompletableFuture<WalkResult> walk;
        try {
            final Target<?> target = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getTarget(snmp, snmpBuilder, snmpEndpoint, secretResolvers);
            walk = walkColumnsAsync(snmp, target, snmpEndpoint, new OID[]{IFX_NAME, IFX_HIGH_SPEED, IFX_ALIAS},
                    SnmpUtils::ifXRow, deadlineNanos, timer)
                    .thenCompose(ifXTable -> shouldFallback(ifXTable)
                            ? walkColumnsAsync(snmp, target, snmpEndpoint, new OID[]{IF_DESCR}, SnmpUtils::ifRow,
                                    deadlineNanos, timer)
                            : CompletableFuture.completedFuture(ifXTable));
        } catch (final IOException | RuntimeException e) {
            // nothing was started, so nothing will complete and close it
            closeQuietly(snmp);
            throw e;
        }
        return walk.whenCompleteAsync((table, failure) -> closeQuietly(snmp), task -> offCallbackThread(timer, task));
    }

    /**
     * Runs {@code task} on {@code timer}. Once the timer is shut down, on a thread of its own:
     * the task still has to run, since it completes a future somebody holds.
     */
    private static void offCallbackThread(final ScheduledExecutorService timer, final Runnable task) {
        try {
            timer.execute(task);
        } catch (final RejectedExecutionException e) {
            Thread.ofPlatform().daemon().name("snmp-walk-close").start(task);
        }
    }

    private static void closeQuietly(final Snmp snmp) {
        try {
            snmp.close();
        } catch (final IOException e) {
            log.debug("Closing SNMP session: {}", e.getMessage());
        }
    }

    /**
     * The fallback exists for agents that lack ifXTable: v2c/v3 agents answer clean-empty (OK),
     * v1 agents answer with a noSuchName error (ERROR). A TIMEOUT means the agent does not
     * answer at all — the fallback walk would only time out again (#337).
     */
    static boolean shouldFallback(final WalkResult ifXTableResult) {
        return switch (ifXTableResult.outcome()) {
            // an abandoned walk would only be abandoned again: the bound is ours, not the table's
            case TIMEOUT, ABANDONED -> false;
            case ERROR -> true;
            case OK -> ifXTableResult.rows().isEmpty();
        };
    }
}
