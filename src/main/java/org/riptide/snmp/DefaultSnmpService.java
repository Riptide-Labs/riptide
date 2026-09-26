/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.secrets.SecretResolvers;
import org.riptide.snmp.collect.CollectedTable;
import org.riptide.snmp.collect.CollectionDefinition;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.fluent.SnmpBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Service
@Slf4j
public class DefaultSnmpService implements SnmpService {

    private final SecretResolvers secretResolvers;
    private final Map<SnmpVersion, Snmp> sessions = new EnumMap<>(SnmpVersion.class);
    private final Map<SnmpVersion, SnmpBuilder> builders = new EnumMap<>(SnmpVersion.class);
    /**
     * Fires every walk's deadline, and closes each per-walk session once its walk completes. One
     * thread for the whole service: neither job waits on an agent.
     */
    private final ScheduledExecutorService deadlines = deadlineTimer();

    /**
     * Remove-on-cancel, because nearly every deadline is cancelled: the walk finishes first. A
     * cancelled task left queued until its delay passes would keep its collector, and every row
     * that collector holds, reachable for the whole walk budget.
     */
    private static ScheduledExecutorService deadlineTimer() {
        final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            final Thread thread = new Thread(runnable, "snmp-walk-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /**
     * Walk accounting. This is the layer where a walk actually happens, so every increment here is
     * a real table walk against a real agent rather than a lookup served from
     * {@link InterfaceSnapshotPoller}'s snapshot. That distinction is the whole point of metering
     * here and not at the layer callers actually use.
     *
     * <p>One walk is roughly ⌈interfaces / 10⌉ GETBULK round trips, because {@code TableUtils}
     * defaults to ten rows per PDU and {@link SnmpUtils} does not override it. The walk rate is
     * therefore the honest measure of what riptide costs an exporter's CPU.
     */
    private final Meter walks;
    private final Timer walkDuration;
    private final Meter walksSucceeded;
    private final Meter walksTimedOut;
    /** Walks stopped at riptide's own bounds (budget or row cap), not by the agent going quiet. */
    private final Meter walksAbandoned;
    private final Meter walksFailed;

    private final Meter collects;
    private final Timer collectDuration;
    private final Meter collectsFailed;

    public DefaultSnmpService(final SecretResolvers secretResolvers, final MetricRegistry metrics) {
        this.secretResolvers = Objects.requireNonNull(secretResolvers);
        Objects.requireNonNull(metrics);
        this.walks = metrics.meter(MetricRegistry.name("snmp", "walks"));
        this.walkDuration = metrics.timer(MetricRegistry.name("snmp", "walkDuration"));
        this.walksSucceeded = metrics.meter(MetricRegistry.name("snmp", "walks", "succeeded"));
        this.walksTimedOut = metrics.meter(MetricRegistry.name("snmp", "walks", "timedOut"));
        this.walksAbandoned = metrics.meter(MetricRegistry.name("snmp", "walks", "abandoned"));
        this.walksFailed = metrics.meter(MetricRegistry.name("snmp", "walks", "failed"));
        this.collects = metrics.meter(MetricRegistry.name("snmp", "collects"));
        this.collectDuration = metrics.timer(MetricRegistry.name("snmp", "collectDuration"));
        this.collectsFailed = metrics.meter(MetricRegistry.name("snmp", "collects", "failed"));
    }

    @Override
    public Optional<IfInfo> getIfInfo(final SnmpEndpoint snmpEndpoint, final int ifIndex) {
        // Walks the whole table and keeps one row. Retained for callers that genuinely want a
        // single interface; anything resolving more than one from the same exporter should use
        // walkInterfaces instead, because this discards the rows that walk already paid for.
        return Optional.ofNullable(walkInterfaces(snmpEndpoint).rows().get(ifIndex));
    }

    @Override
    public CompletableFuture<InterfaceTable> walkInterfacesAsync(final SnmpEndpoint snmpEndpoint) {
        this.walks.mark();
        final Timer.Context timing = this.walkDuration.time();
        final CompletableFuture<SnmpUtils.WalkResult> walk;
        try {
            walk = SnmpUtils.getIfInfoMapAsync(snmpEndpoint, this.secretResolvers,
                    SnmpUtils.WALK_BUDGET.toNanos(), this.deadlines);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: an unresolvable secret reference must degrade to an
            // unenriched flow, never fail the pipeline and drop the batch.
            timing.stop();
            this.walksFailed.mark();
            log.warn("Error walking the interface table of {}: {}", snmpEndpoint, e.getMessage());
            return CompletableFuture.completedFuture(new InterfaceTable(Map.of(), true));
        }
        return walk.whenComplete((result, failure) -> timing.stop())
                .thenApply(result -> {
                    switch (result.outcome()) {
                        case OK -> this.walksSucceeded.mark();
                        case TIMEOUT -> this.walksTimedOut.mark();
                        case ERROR -> this.walksFailed.mark();
                        case ABANDONED -> this.walksAbandoned.mark();
                    }
                    // any non-OK outcome is a failed walk: an error PDU is no more worth retrying
                    // immediately than a timeout, and the meters above keep the distinction
                    return new InterfaceTable(result.rows(), result.outcome() != SnmpUtils.WalkOutcome.OK);
                });
    }

    /**
     * One session per version, built on first use. Closing the service closes them all. The
     * {@code SnmpBuilder} is kept alongside the session because {@link SnmpVersion#getTarget}
     * configures the target on the already-built builder, which is why every builder sets
     * {@code allowIncrementalConfigAfterBuild()}.
     *
     * <p><strong>Known limitation</strong>: {@code builder.build()} can throw {@code IOException}
     * from {@code Snmp.listen()} after the builder's earlier {@code .udp()}/{@code .threads(2)}
     * calls have already opened the UDP socket and started the two dispatcher threads, and on
     * that failure this method leaks both, because snmp4j 3.13.1's {@code SnmpBuilder} gives no
     * public way to reach or close that pre-built {@code Snmp}: its {@code snmp} field is
     * {@code protected}, there is no getter, and the only constructor that accepts an
     * externally-supplied {@code Snmp} is also {@code protected} (verified by disassembling
     * {@code SnmpBuilder.build()}: it is exactly one field read plus {@code snmp.listen()}).
     * Reaching it would require subclassing into those protected internals, which is leaning on
     * an implementation detail rather than a supported extension point — not done here. Every
     * later {@code collect} against this version retries {@code getSnmpBuilder().build()} and
     * leaks another socket/thread pair until {@code build()} succeeds. {@code build()} failing
     * at all is rare in practice ({@code listen()} mostly fails when the earlier {@code .udp()}
     * call would already have failed), but this is a real, undischarged bound.
     */
    private synchronized Snmp session(final SnmpVersion version) throws IOException {
        Snmp snmp = this.sessions.get(version);
        if (snmp == null) {
            final SnmpBuilder builder = version.getSnmpBuilder();
            snmp = builder.build();
            this.builders.put(version, builder);
            this.sessions.put(version, snmp);
        }
        return snmp;
    }

    @Override
    public CompletableFuture<CollectedTable> collectAsync(final SnmpEndpoint endpoint,
                                                          final CollectionDefinition definition,
                                                          final Duration budget) {
        this.collects.mark();
        final long deadline = System.nanoTime() + budget.toNanos();
        final Timer.Context timing = this.collectDuration.time();
        final CompletableFuture<CollectedTable> collect;
        try {
            final SnmpVersion version = endpoint.getSnmpDefinition().getSnmpVersion();
            final Snmp snmp = session(version);
            final Target<?> target = version.getTarget(snmp, this.builders.get(version), endpoint, this.secretResolvers);
            collect = SnmpUtils.collectAsync(snmp, target, endpoint, definition, deadline, this.deadlines);
        } catch (IOException | IllegalArgumentException e) {
            timing.stop();
            this.collectsFailed.mark();
            log.warn("SNMP collect against {} failed: {}", endpoint, e.getMessage());
            return CompletableFuture.completedFuture(new CollectedTable(Map.of(), true));
        }
        return collect.whenComplete((table, failure) -> {
            timing.stop();
            if (table != null && table.walkFailed()) {
                this.collectsFailed.mark();
            }
        });
    }

    /** Test seam. */
    synchronized int openSessions() {
        return this.sessions.size();
    }

    /**
     * Closes the shared sessions, which completes every walk still pending on them, then stops
     * the deadline timer taking new work. A walk still pending on a per-walk session completes at
     * snmp4j's own timeout and closes its session on a thread of its own.
     *
     * <p>The sessions are closed outside the monitor. {@code Snmp.close()} joins snmp4j's
     * dispatcher threads, and a dispatcher thread running a continuation that calls
     * {@link #session} would wait on this monitor forever, so the join would never return.</p>
     */
    @PreDestroy
    public void close() {
        final List<Snmp> open;
        synchronized (this) {
            open = new ArrayList<>(this.sessions.values());
            this.sessions.clear();
            this.builders.clear();
        }
        for (final Snmp snmp : open) {
            try {
                snmp.close();
            } catch (final IOException e) {
                log.debug("Closing SNMP session: {}", e.getMessage());
            }
        }
        // shutdown, not shutdownNow: queued tasks still run. A per-walk session's close is
        // queued here with execute, and draining it unrun would leave that walk's session
        // open and its future, and the poller permit behind it, never completed. Delayed
        // deadlines still fire too. The thread is a daemon, so none of this delays exit
        this.deadlines.shutdown();
    }
}
