/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.session.UdpSessionManager.SessionKey;
import org.riptide.pipeline.ExporterIdentity;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Decides whether session state may be allocated for an exporter scope identity, so the tables fed
 * from unauthenticated UDP stay within a bound an operator can compute.
 *
 * <p>Two levels, and which policy sits at which level is the load-bearing decision:
 *
 * <pre>
 *   sources : Map&lt;SessionKey, ScopeBudget&gt;   &lt;= maxSources          reject-new + idle evict
 *   scopes  : per source, admitted identities  &lt;= maxScopesPerSource  LRU *within* that source
 * </pre>
 *
 * <p>Global LRU across identities would be a hole rather than a bound: an attacker's inserts would
 * evict other exporters' state, letting the attacker choose which devices stop being monitored.
 * Reject-new on the source table lets a flood block <em>new</em> exporters while incumbents survive,
 * which is the lesser harm and the same trade {@code InterfaceSnapshotPoller} already makes for its
 * exporter bound. LRU confined to one source's own budget keeps the blast radius on the attacker's
 * own source, and forces a spoofing attacker to sustain traffic on every forged address to hold its
 * slots — which is what removes the fire-and-forget property.
 *
 * <p><strong>Evictions must be acted on.</strong> Admitting a new scope by evicting this source's
 * least-recently-used one only bounds anything if the evicted scope's table entries go with it;
 * otherwise the budget shrinks while the tables it governs keep growing. {@link #admit} therefore
 * hands the evicted identity to a callback rather than returning a bare boolean, and the caller is
 * responsible for dropping the corresponding state.
 *
 * <p>Thread-safe. The steady-state path — a packet from an already-admitted scope — takes one
 * uncontended lock on that source's budget and no allocation. Each source has its own lock, so
 * unrelated exporters never contend.
 */
@Slf4j
public final class SessionAdmission {

    /** At most one rejection warning per source per this interval, so a flood cannot flood the log. */
    private static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final SessionAdmissionConfig config;
    private final LongSupplier nanoTime;

    private final ConcurrentMap<SessionKey, ScopeBudget> sources = new ConcurrentHashMap<>();

    private final Meter rejectedSources;
    private final Meter rejectedScopes;
    /**
     * One limiter per condition, not one shared.
     *
     * <p>They do not carry the same weight: hitting the scope budget is routine under a spray and
     * costs that source its least-recently-used scope, while hitting the source bound means new
     * exporters are no longer retained at all. Sharing a limiter let the noisy signal suppress the
     * serious one, indefinitely, since a flood produces the noisy one continuously.
     */
    private final AtomicLong lastSourceWarnNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastScopeWarnNanos = new AtomicLong(Long.MIN_VALUE);

    public SessionAdmission(final SessionAdmissionConfig config, final MetricRegistry metrics) {
        this(config, metrics, System::nanoTime);
    }

    /** Test seam: a controllable clock, so idle reclamation can be exercised without sleeping. */
    SessionAdmission(final SessionAdmissionConfig config,
                     final MetricRegistry metrics,
                     final LongSupplier nanoTime) {
        this.config = Objects.requireNonNull(config);
        this.config.validate();
        this.nanoTime = Objects.requireNonNull(nanoTime);

        this.rejectedSources = metrics.meter(MetricRegistry.name("flows", "session", "rejectedSources"));
        this.rejectedScopes = metrics.meter(MetricRegistry.name("flows", "session", "rejectedScopes"));
        // Gauges rather than counters: the population is derivable from the maps themselves, and a
        // counter would drift the first time an eviction path forgot to decrement it.
        metrics.gauge(MetricRegistry.name("flows", "session", "sources"), () -> this::sourceCount);
        metrics.gauge(MetricRegistry.name("flows", "session", "scopes"), () -> this::scopeCount);
    }

    /**
     * Whether state may be allocated for {@code scope} arriving on {@code source}.
     *
     * @param onEvicted receives the identity of a scope displaced from this source's budget to make
     *                  room. The caller MUST drop that scope's state; see the class comment.
     * @return {@code false} when the source table is full and this source is not already admitted,
     *         in which case no state may be allocated for it at all
     */
    public boolean admit(final SessionKey source,
                         final ExporterIdentity scope,
                         final Consumer<ExporterIdentity> onEvicted) {
        final ScopeBudget budget = budgetFor(source);
        if (budget == null) {
            return false;
        }
        final ExporterIdentity evicted = budget.admit(scope, this.config.getMaxScopesPerSource(), now());
        if (evicted != null) {
            this.rejectedScopes.mark();
            warnRateLimited(source, scope);
            onEvicted.accept(evicted);
        }
        return true;
    }

    /**
     * This source's budget, admitting the source itself if there is room.
     *
     * <p>Same shape as {@code InterfaceSnapshotPoller.register}: a lock-free hit for the common
     * case, a size check before inserting, and a re-check afterwards because between the check and
     * the insert another thread may have taken the last slot. The re-check removes only the mapping
     * this call created, so a racing thread's admitted source is never revoked.
     */
    private ScopeBudget budgetFor(final SessionKey source) {
        final ScopeBudget existing = this.sources.get(source);
        if (existing != null) {
            existing.touch(now());
            return existing;
        }
        final int maxSources = this.config.getMaxSources();
        if (maxSources <= 0 || this.sources.size() >= maxSources) {
            this.rejectedSources.mark();
            warnRateLimited(source, null);
            return null;
        }
        final AtomicBoolean isNew = new AtomicBoolean(false);
        final ScopeBudget created = this.sources.computeIfAbsent(source, key -> {
            isNew.set(true);
            return new ScopeBudget(now());
        });
        if (isNew.get() && this.sources.size() > maxSources) {
            this.sources.remove(source, created);
            this.rejectedSources.mark();
            warnRateLimited(source, null);
            return null;
        }
        return created;
    }

    /**
     * Release sources unheard for longer than the configured idle timeout.
     *
     * <p>This is what makes the bound recover on its own: without it a flood would hold every slot
     * until restart, and the first legitimate exporter to appear afterwards would be rejected.
     *
     * <p>Only budget slots are released here, not the tables themselves. Each session manager
     * already expires its own idle templates and sequence trackers on the same timer, so making
     * this drive table eviction too would duplicate that — and could not work anyway once this
     * oracle is shared across parsers, since it has no way to know which manager holds the state
     * for a given source.
     */
    public void reclaimIdle() {
        final long cutoff = now() - this.config.getSourceIdleTimeout().toNanos();
        for (final Map.Entry<SessionKey, ScopeBudget> entry : this.sources.entrySet()) {
            final ScopeBudget budget = entry.getValue();
            // Subtraction rather than <, so the comparison stays correct across nanoTime wrapping.
            if (budget.lastSeenNanos - cutoff <= 0) {
                // remove(key, value) rather than remove(key): a packet may have arrived and
                // refreshed lastSeen since the test above, and dropping the budget then would
                // revoke a source that is demonstrably live.
                this.sources.remove(entry.getKey(), budget);
            }
        }
    }

    /**
     * How long a source may go unheard before its budget is released.
     *
     * <p>Exposed so a caller holding the matching table TTL can check the two agree. The budget slot
     * and the state it authorises are reclaimed by different timers, which is only harmless while
     * this is the shorter of the two.
     */
    public Duration sourceIdleTimeout() {
        return this.config.getSourceIdleTimeout();
    }

    /** Distinct sources currently holding a budget. */
    public int sourceCount() {
        return this.sources.size();
    }

    /** Admitted scope identities across every source. Walks the sources, so not for the packet path. */
    public int scopeCount() {
        int total = 0;
        for (final ScopeBudget budget : this.sources.values()) {
            total += budget.size();
        }
        return total;
    }

    private long now() {
        return this.nanoTime.getAsLong();
    }

    /**
     * One warning per interval per condition, not per source: the case worth logging is a flood,
     * and a flood by definition arrives from many identities at once.
     */
    private void warnRateLimited(final SessionKey source, final ExporterIdentity scope) {
        final AtomicLong limiter = scope == null ? this.lastSourceWarnNanos : this.lastScopeWarnNanos;
        final long now = now();
        final long last = limiter.get();
        if (now - last < WARN_INTERVAL_NANOS && last != Long.MIN_VALUE) {
            return;
        }
        if (!limiter.compareAndSet(last, now)) {
            return;
        }
        if (scope == null) {
            log.warn("Session source bound ({}) reached; state for new exporters is not being retained. "
                            + "Last refused: {}. Raise riptide.flows.session.max-sources if this fleet is "
                            + "genuinely larger, or restrict the flow port to known exporters.",
                    this.config.getMaxSources(), source.getDescription());
        } else {
            log.warn("Scope budget ({}) reached for source {}; its least-recently-used scope was "
                            + "displaced to admit {}. Raise riptide.flows.session.max-scopes-per-source "
                            + "if this exporter genuinely exports that many observation domains.",
                    this.config.getMaxScopesPerSource(), source.getDescription(), scope);
        }
    }

    /**
     * One source's admitted scope identities, least-recently-used first.
     *
     * <p>A {@link LinkedHashMap} in access order under a lock, rather than a lock-free structure:
     * LRU needs a total order over accesses, and every concurrent approximation of that is either
     * wrong under contention or larger than the problem. The lock is per source and held for a map
     * operation, so packets from different exporters never wait on each other.
     */
    private static final class ScopeBudget {
        private final Map<ExporterIdentity, Boolean> admitted = new LinkedHashMap<>(16, 0.75f, true);
        private volatile long lastSeenNanos;

        private ScopeBudget(final long nowNanos) {
            this.lastSeenNanos = nowNanos;
        }

        private void touch(final long nowNanos) {
            this.lastSeenNanos = nowNanos;
        }

        /**
         * @return the identity displaced to make room, or {@code null} if none was
         */
        private synchronized ExporterIdentity admit(final ExporterIdentity scope,
                                                    final int maxScopes,
                                                    final long nowNanos) {
            this.lastSeenNanos = nowNanos;
            // get() rather than containsKey(): access order only updates on get/put, so containsKey
            // would leave a busy scope looking idle and make it the next eviction victim.
            if (this.admitted.get(scope) != null) {
                return null;
            }
            ExporterIdentity evicted = null;
            // No `maxScopes > 0` guard: it would make a misconfigured zero mean "no bound" rather
            // than "no room", restoring the unbounded growth this class exists to stop. The
            // constructor rejects a non-positive value outright, so the bound is always enforced.
            if (this.admitted.size() >= maxScopes) {
                final Iterator<ExporterIdentity> lruFirst = this.admitted.keySet().iterator();
                evicted = lruFirst.next();
                lruFirst.remove();
            }
            this.admitted.put(scope, Boolean.TRUE);
            return evicted;
        }

        private synchronized int size() {
            return this.admitted.size();
        }
    }
}
