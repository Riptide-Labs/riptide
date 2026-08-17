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
     * bounds how many round-trips a table walk takes, so walk duration is agent-paced. A
     * full getBulk walk of thousands of interfaces is seconds, so two minutes is generous
     * by orders of magnitude; the effective budget is {@code min(refresh-interval, this)},
     * because a walk that outlives its own cadence is definitionally pathological (#536).
     */
    static final Duration WALK_BUDGET_CEILING = Duration.ofMinutes(2);

    /**
     * Upper bound on collected rows. Real devices top out in the low thousands of
     * interfaces; this exists because the row count is agent-controlled, and an agent
     * serving a strictly increasing, never-ending table (each response inside the
     * per-request timeout) would otherwise walk forever while the collected list grows
     * without bound. snmp4j already rejects non-increasing OIDs; this closes the
     * increasing-forever case.
     */
    static final int MAX_TABLE_ROWS = 65_536;

    private SnmpUtils() {
    }

    enum WalkOutcome {
        OK, TIMEOUT, ERROR
    }

    public record WalkResult(Map<Integer, IfInfo> rows, WalkOutcome outcome) {
    }

    private static WalkResult walkColumns(final Snmp snmp, final Target<?> target, final SnmpEndpoint snmpEndpoint,
                                          final OID[] columns, final Function<List<VariableBinding>, IfInfo> row,
                                          final long deadlineNanos) {
        final TableUtils tableUtils = new TableUtils(snmp, new DefaultPDUFactory());
        // the listener variant, not the synchronous one: with getTable(target, columns,
        // lower, upper) the blocking wait belongs to snmp4j and the loop termination
        // belongs to the AGENT — walk duration and heap were both agent-controlled. Here
        // the wait is ours (bounded below) and the collector stops at the row cap
        final WalkCollector collector = new WalkCollector(MAX_TABLE_ROWS);
        tableUtils.getTable(target, columns, collector, null, null, null);

        if (!collector.await(deadlineNanos - System.nanoTime())) {
            // rate-bounded by the poller's back-off, which the TIMEOUT outcome engages
            log.warn("Interface walk of {} exceeded its budget and was abandoned; rows collected "
                    + "so far are discarded (an incomplete table must not be cached as complete)",
                    snmpEndpoint);
            return new WalkResult(new TreeMap<>(), WalkOutcome.TIMEOUT);
        }
        if (collector.capped()) {
            log.warn("Interface walk of {} stopped at the {}-row cap: the table kept growing, which "
                    + "no real device does. Rows are discarded and the endpoint backs off",
                    snmpEndpoint, MAX_TABLE_ROWS);
            return new WalkResult(new TreeMap<>(), WalkOutcome.TIMEOUT);
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
            if (event.isError() || this.events.size() >= this.maxRows) {
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
         * expiry, after which late deliveries are dropped ({@code next} answers false and
         * snmp4j stops). The wait being ours is the whole point: no failure mode inside
         * snmp4j — undelivered response, dead dispatcher — can keep the walker thread.
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
        final SnmpBuilder snmpBuilder = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getSnmpBuilder();
        // one deadline for the whole call: the fallback walk shares the budget rather than
        // doubling it, and a walk may not outlive its own cadence (a shorter profile
        // refresh tightens the budget; the ceiling does the work for slow cadences)
        final long deadlineNanos = System.nanoTime() + walkBudget(snmpEndpoint).toNanos();
        try (Snmp snmp = snmpBuilder.build()) {
            final Target<?> target = snmpEndpoint.getSnmpDefinition().getSnmpVersion().getTarget(snmp, snmpBuilder, snmpEndpoint, secretResolvers);
            final var ifXTable = walkColumns(snmp, target, snmpEndpoint, new OID[]{IFX_NAME, IFX_HIGH_SPEED, IFX_ALIAS}, SnmpUtils::ifXRow, deadlineNanos);
            if (shouldFallback(ifXTable)) {
                return walkColumns(snmp, target, snmpEndpoint, new OID[]{IF_DESCR}, SnmpUtils::ifRow, deadlineNanos);
            }
            return ifXTable;
        }
    }

    static Duration walkBudget(final SnmpEndpoint snmpEndpoint) {
        final Duration cadence = snmpEndpoint.getRefreshInterval();
        return cadence == null || cadence.compareTo(WALK_BUDGET_CEILING) > 0
                ? WALK_BUDGET_CEILING
                : cadence;
    }

    /**
     * The fallback exists for agents that lack ifXTable: v2c/v3 agents answer clean-empty (OK),
     * v1 agents answer with a noSuchName error (ERROR). A TIMEOUT means the agent does not
     * answer at all — the fallback walk would only time out again (#337).
     */
    static boolean shouldFallback(final WalkResult ifXTableResult) {
        return switch (ifXTableResult.outcome()) {
            case TIMEOUT -> false;
            case ERROR -> true;
            case OK -> ifXTableResult.rows().isEmpty();
        };
    }
}
