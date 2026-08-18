/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.riptide.secrets.SecretResolvers;
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
     * Upper bound on one whole {@link #getIfInfoMap} call, including the ifTable fallback
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
     * minutes of one pool slot per back-off cycle.</p>
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
     * (collected rows are buffered before the poller snapshots them, times pool width in
     * the adversarial case) and should happen against a real request, not speculation.</p>
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

    private static WalkResult walkColumns(final Snmp snmp, final Target<?> target, final SnmpEndpoint snmpEndpoint,
                                          final OID[] columns, final Function<List<VariableBinding>, IfInfo> row,
                                          final long deadlineNanos) {
        if (deadlineNanos - System.nanoTime() <= 0) {
            // checked before the request reaches the wire: the fallback walk after a slow
            // ifXTable walk would otherwise fire a real GETBULK, abandon it instantly, and
            // leave snmp4j delivering into a closing session
            return new WalkResult(new TreeMap<>(), WalkOutcome.ABANDONED);
        }
        final TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
        // belt and braces with the collector's own cap: the library stops issuing requests
        // at the same boundary instead of relying on the listener's refusal alone. Plus
        // one because the cap is exclusive — the collector must see a cap-exceeding row
        // to distinguish "more than the cap" from "complete at exactly the cap"
        tableUtils.setRowLimit(MAX_TABLE_ROWS + 1);
        // the listener variant, not the synchronous one: with getTable(target, columns,
        // lower, upper) the blocking wait belongs to snmp4j and the loop termination
        // belongs to the AGENT — walk duration and heap were both agent-controlled. Here
        // the wait is ours. (The synchronous timeout variant was considered and rejected:
        // its wait is a bare listener.wait with a single !finished check — spurious-wakeup
        // prone — at one-second granularity.)
        final WalkCollector collector = new WalkCollector(MAX_TABLE_ROWS);
        tableUtils.getTable(target, columns, collector, null, null, null);

        if (!collector.await(deadlineNanos - System.nanoTime())) {
            if (Thread.currentThread().isInterrupted()) {
                // shutdown, not the agent's fault: the walker pool was interrupted while a
                // walk was in flight. Quietly abandoned; the process is exiting
                return new WalkResult(new TreeMap<>(), WalkOutcome.ABANDONED);
            }
            // rate-bounded by the poller's back-off, which the failed outcome engages
            log.warn("Interface walk of {} exceeded its {} budget and was abandoned; rows collected "
                    + "so far are discarded (an incomplete table must not be cached as complete)",
                    snmpEndpoint, WALK_BUDGET);
            return new WalkResult(new TreeMap<>(), WalkOutcome.ABANDONED);
        }
        if (collector.capped()) {
            log.warn("Interface walk of {} stopped at the {}-row cap and was abandoned: either the "
                    + "table keeps growing (no real access device does this) or the device carries "
                    + "more interfaces than riptide enriches. Rows are discarded and the endpoint "
                    + "backs off", snmpEndpoint, MAX_TABLE_ROWS);
            return new WalkResult(new TreeMap<>(), WalkOutcome.ABANDONED);
        }

        final Map<Integer, IfInfo> interfaces = new TreeMap<>();

        for (final TableEvent tableEvent : collector.events()) {
            if (tableEvent.isError()) {
                // The SNMP4J target must not be logged: its toString() carries the credential (#335)
                log.warn("Error querying {} for {}: {}", columns[0], snmpEndpoint, tableEvent.getErrorMessage());
                // rows collected before the error are discarded: an incomplete table must not
                // be cached as if it were complete
                final var outcome = tableEvent.getStatus() == TableEvent.STATUS_TIMEOUT
                        ? WalkOutcome.TIMEOUT
                        : WalkOutcome.ERROR;
                return new WalkResult(new TreeMap<>(), outcome);
            }
            if (tableEvent.getIndex() == null || tableEvent.getColumns() == null) {
                continue;
            }

            final int ifIndex = tableEvent.getIndex().last();
            // Arrays.asList, not List.of: sparse tables leave null entries for missing columns
            final IfInfo ifInfo = row.apply(Arrays.asList(tableEvent.getColumns()));
            if (ifInfo != null) {
                interfaces.put(ifIndex, ifInfo);
            }
        }

        return new WalkResult(interfaces, WalkOutcome.OK);
    }

    /**
     * Collects row events and owns the two bounds. Thread contract: snmp4j delivers
     * {@code next}/{@code finished} on its dispatcher thread while the walker thread waits
     * in {@code await}; the latch provides the happens-before for reading the collected
     * list, and the monitor keeps a late delivery from racing an abandonment.
     */
    static final class WalkCollector implements TableListener {

        private final List<TableEvent> events = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final int maxRows;
        private boolean finished;
        private boolean capped;

        WalkCollector(final int maxRows) {
            this.maxRows = maxRows;
        }

        @Override
        public synchronized boolean next(final TableEvent event) {
            if (this.finished) {
                return false;
            }
            this.events.add(event);
            // strictly greater: the maxRows-th row may be the table's last, and its clean
            // finished() must win over a false cap
            if (event.isError() || this.events.size() > this.maxRows) {
                this.capped = !event.isError();
                this.finished = true;
                this.done.countDown();
                return false;
            }
            return true;
        }

        @Override
        public synchronized void finished(final TableEvent event) {
            if (!this.finished) {
                if (event != null && (event.isError() || event.getIndex() != null)) {
                    this.events.add(event);
                }
                this.finished = true;
                this.done.countDown();
            }
        }

        @Override
        public synchronized boolean isFinished() {
            return this.finished;
        }

        /**
         * Waits for the walk to finish, up to the remaining budget. Returns false on
         * expiry, after which late deliveries are dropped ({@code next} answers false).
         * The wait being ours is the whole point: no failure mode inside snmp4j —
         * undelivered response, dead dispatcher — can keep the walker thread.
         *
         * <p>What actually stops the round-trips after an abandonment is
         * {@code getIfInfoMap}'s try-with-resources: {@code Snmp.close()} cancels the
         * pending request and delivers a final refused event. TableUtils never consults
         * {@code isFinished()} on its own, so the refusing {@code next} plus the close are
         * the whole mechanism — an invariant this class depends on and must keep.</p>
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

        private synchronized void abandon() {
            this.finished = true;
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

    private static Long number(final VariableBinding vb) {
        if (vb == null || vb.getVariable() == null || vb.getVariable().isException()) {
            return null;
        }
        return vb.getVariable().toLong();
    }

    /**
     * Walks the exporter's interface table: ifXTable (ifName/ifHighSpeed/ifAlias) first,
     * falling back to the legacy ifTable (ifDescr only) — unless the first walk timed out.
     */
    public static WalkResult getIfInfoMap(final SnmpEndpoint snmpEndpoint, final SecretResolvers secretResolvers) throws IOException {
        return getIfInfoMap(snmpEndpoint, secretResolvers, WALK_BUDGET.toNanos());
    }

    /** Test seam: the budget parameter lets the abandonment paths run against a real agent. */
    static WalkResult getIfInfoMap(final SnmpEndpoint snmpEndpoint, final SecretResolvers secretResolvers,
                                   final long budgetNanos) throws IOException {
        final SnmpBuilder snmpBuilder = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getSnmpBuilder();
        // one deadline for the whole call: the fallback walk shares the budget rather than
        // doubling it
        final long deadlineNanos = System.nanoTime() + budgetNanos;
        try (Snmp snmp = snmpBuilder.build()) {
            final Target<?> target = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getTarget(snmp, snmpBuilder, snmpEndpoint, secretResolvers);
            final var ifXTable = walkColumns(snmp, target, snmpEndpoint, new OID[]{IFX_NAME, IFX_HIGH_SPEED, IFX_ALIAS}, SnmpUtils::ifXRow, deadlineNanos);
            if (shouldFallback(ifXTable)) {
                return walkColumns(snmp, target, snmpEndpoint, new OID[]{IF_DESCR}, SnmpUtils::ifRow, deadlineNanos);
            }
            return ifXTable;
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
