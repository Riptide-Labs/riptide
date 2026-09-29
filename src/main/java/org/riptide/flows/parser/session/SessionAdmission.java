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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 *   sources : Map&lt;SessionKey, ScopeBudget&gt;   &lt;= maxSources          reject-new + idle evict + same-host quiet replace
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
 * <p><strong>A full table still admits a restarted exporter.</strong> IPFIX keys its session on the
 * full remote socket, so an exporter that restarts on a new source port is a new source, and its
 * old socket keeps a slot until it idles out. At a full table that refused the restarted exporter
 * for the whole idle timeout (#946). So a new source is not refused outright when its exporter host
 * already holds a quiet slot: it takes the slot of that host's least-recently-seen source, and the
 * replaced source's scopes go to the eviction callback. Replacement never crosses hosts, and never
 * takes a slot heard from within {@link #REPLACEABLE_AFTER_NANOS}, so neither a flood nor a sender
 * spoofing a live exporter's address can displace a live exporter. An exporter quiet for longer is
 * not protected: at a full table, a sender spoofing its address can take its slot and keep it with
 * one packet a minute. That is the price of admitting restarted exporters, and it needs the
 * spoofing a source ACL on the flow port already rules out.
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

    /**
     * How long a source must be quiet before a new source of the same host may take its slot.
     *
     * <p>Not a key, because it is not a sizing decision. It only has to separate a socket that
     * stopped (a restarted exporter's old one, minutes quiet) from one that is live (a datagram every
     * few seconds). Without it, one spoofed packet from a live exporter's address on a new port would
     * replace that exporter's session at a full table.
     */
    static final long REPLACEABLE_AFTER_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final SessionAdmissionConfig config;
    private final LongSupplier nanoTime;

    private final ConcurrentMap<SessionKey, ScopeBudget> sources = new ConcurrentHashMap<>();

    /**
     * Each exporter host's admitted sources, so a replacement finds its candidates without walking
     * the source table. A walk per refused packet would let a flood buy CPU with the bound itself.
     *
     * <p>Every set is mutated only inside {@code compute} on its host, so an emptied set is removed
     * atomically. Entries may briefly name a source already gone from {@link #sources}; readers
     * treat those as absent.
     */
    private final ConcurrentMap<Object, Set<SessionKey>> hosts = new ConcurrentHashMap<>();

    private final Meter rejectedSources;
    private final Meter rejectedScopes;
    private final Meter replacedSources;
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
        this.replacedSources = metrics.meter(MetricRegistry.name("flows", "session", "replacedSources"));
        // Gauges rather than counters: the population is derivable from the maps themselves, and a
        // counter would drift the first time an eviction path forgot to decrement it.
        metrics.gauge(MetricRegistry.name("flows", "session", "sources"), () -> this::sourceCount);
        metrics.gauge(MetricRegistry.name("flows", "session", "scopes"), () -> this::scopeCount);
    }

    /**
     * Whether state may be allocated for {@code scope} arriving on {@code source}.
     *
     * @param onEvicted receives each session and scope displaced to make room. The caller MUST drop
     *                  that state; see the class comment. The session is not always {@code source}:
     *                  a replacement hands over every scope of the replaced source.
     * @return {@code false} when the source table is full, this source is not already admitted, and
     *         its host holds no replaceable source, in which case no state may be allocated for it
     */
    public boolean admit(final SessionKey source,
                         final ExporterIdentity scope,
                         final Consumer<AdmittedScope> onEvicted) {
        final ScopeBudget budget = budgetFor(source, onEvicted);
        if (budget == null) {
            return false;
        }
        final ExporterIdentity evicted = budget.admit(scope, this.config.getMaxScopesPerSource(), now());
        if (evicted != null) {
            this.rejectedScopes.mark();
            warnRateLimited(source, scope);
            onEvicted.accept(new AdmittedScope(source, evicted));
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
    private ScopeBudget budgetFor(final SessionKey source, final Consumer<AdmittedScope> onEvicted) {
        final ScopeBudget existing = this.sources.get(source);
        if (existing != null) {
            existing.touch(now());
            return existing;
        }
        final int maxSources = this.config.getMaxSources();
        boolean replaced = false;
        if (maxSources <= 0 || this.sources.size() >= maxSources) {
            replaced = replaceQuietSibling(source, onEvicted);
            if (!replaced) {
                this.rejectedSources.mark();
                warnRateLimited(source, null);
                return null;
            }
        }
        final AtomicBoolean isNew = new AtomicBoolean(false);
        final ScopeBudget created = this.sources.computeIfAbsent(source, key -> {
            isNew.set(true);
            return new ScopeBudget(now());
        });
        if (isNew.get()) {
            // A replacement freed the slot it takes, so it skips the re-check. A racing insert that
            // replaced nothing still runs it and backs out; without the skip, that racer could take
            // the freed slot and leave this source refused with its predecessor's state already gone.
            if (!replaced && this.sources.size() > maxSources) {
                this.sources.remove(source, created);
                this.rejectedSources.mark();
                warnRateLimited(source, null);
                return null;
            }
            index(source);
            // Idle reclaim may have removed the source between the insert and the index, and would
            // then have found nothing to unindex. Re-check so the index cannot keep a dead entry.
            if (!this.sources.containsKey(source)) {
                unindex(source);
            }
        }
        return created;
    }

    /**
     * Free a slot for {@code source} by replacing its own host's least-recently-seen source, if that
     * one has been quiet long enough. See the class comment for why this is safe.
     *
     * @return whether a slot was freed
     */
    private boolean replaceQuietSibling(final SessionKey source, final Consumer<AdmittedScope> onEvicted) {
        final Set<SessionKey> siblings = this.hosts.get(source.getExporterHost());
        if (siblings == null) {
            return false;
        }
        SessionKey victim = null;
        ScopeBudget victimBudget = null;
        for (final SessionKey sibling : siblings) {
            final ScopeBudget budget = this.sources.get(sibling);
            // Subtraction rather than <, so the comparison stays correct across nanoTime wrapping.
            if (budget != null && (victimBudget == null || budget.lastSeenNanos - victimBudget.lastSeenNanos < 0)) {
                victim = sibling;
                victimBudget = budget;
            }
        }
        if (victim == null || now() - victimBudget.lastSeenNanos < REPLACEABLE_AFTER_NANOS) {
            return false;
        }
        // remove(key, value), as in reclaimIdle: a racing thread may have replaced it already.
        if (!this.sources.remove(victim, victimBudget)) {
            return false;
        }
        unindex(victim);
        this.replacedSources.mark();
        for (final ExporterIdentity scope : victimBudget.drain()) {
            onEvicted.accept(new AdmittedScope(victim, scope));
        }
        return true;
    }

    private void index(final SessionKey source) {
        this.hosts.compute(source.getExporterHost(), (host, admitted) -> {
            final Set<SessionKey> target = admitted != null ? admitted : ConcurrentHashMap.newKeySet();
            target.add(source);
            return target;
        });
    }

    /**
     * Drop {@code source} from the index unless it is admitted again.
     *
     * <p>A source removed from {@link #sources} can be re-admitted by another thread before this
     * runs. Checking inside {@code compute} on the host serialises this against that thread's
     * {@link #index}, so the fresh entry is never removed.
     */
    private void unindex(final SessionKey source) {
        this.hosts.computeIfPresent(source.getExporterHost(), (host, admitted) -> {
            if (!this.sources.containsKey(source)) {
                admitted.remove(source);
            }
            return admitted.isEmpty() ? null : admitted;
        });
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
                if (this.sources.remove(entry.getKey(), budget)) {
                    unindex(entry.getKey());
                }
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

    /**
     * Sources named in the host index. Must equal {@link #sourceCount()} once admissions settle;
     * anything above it is a leaked index entry. Walks the index, so for tests only.
     */
    int indexedSourceCount() {
        int total = 0;
        for (final Set<SessionKey> admitted : this.hosts.values()) {
            total += admitted.size();
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
     * One scope handed back for its state to be dropped, named with the source that holds it.
     *
     * <p>The source is part of the record because it is not always the caller's: a replacement
     * hands over another source's scopes, and dropping them under the caller's key would drop
     * nothing.
     */
    public record AdmittedScope(SessionKey session, ExporterIdentity scope) {
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

        /** Empty this budget and return what it held, for a replaced source's state to be dropped. */
        private synchronized List<ExporterIdentity> drain() {
            final List<ExporterIdentity> held = new ArrayList<>(this.admitted.keySet());
            this.admitted.clear();
            return held;
        }

        private synchronized int size() {
            return this.admitted.size();
        }
    }
}
