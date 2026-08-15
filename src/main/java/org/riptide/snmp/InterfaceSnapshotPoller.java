/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.Meter;
import inet.ipaddr.IPAddressString;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.pipeline.ExporterIdentity;
import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Walks each exporter's interface table on a schedule and serves enrichment from the result.
 *
 * <p>Replaces demand-filled caching. The difference that matters is not that walks are cached
 * but that <em>flow traffic no longer triggers them</em>: an exporter is registered when its
 * first flow arrives, and from then on its table is re-walked on a timer regardless of how many
 * interfaces its flows reference. Load on an exporter's agent therefore depends on the schedule,
 * not on traffic diversity — which is the opposite of the demand-filled design, where load peaked
 * exactly when the device was busiest because that is when unseen ifIndexes appear.
 *
 * <p>Three properties are structural rather than enforced:
 * <ul>
 *   <li>Enrichment never walks. {@link #trackAndResolve} registers and reads but never issues SNMP,
 *       so no parser thread can block on it.</li>
 *   <li>At most one walk per endpoint is in flight, because each registration carries a single
 *       in-flight flag.</li>
 *   <li>Fleet-wide concurrency is bounded by the walker pool, independently of exporter count.</li>
 * </ul>
 *
 * <p>Walks are spread across the refresh interval by an offset derived from the endpoint address,
 * so the schedule survives a restart without stored state, plus a small per-cycle jitter. The
 * offset only distributes statistically and collisions are expected; the pool absorbs them as a
 * brief queue rather than a burst at the agent. Jitter exists for a different reason — a fixed
 * offset would poll a device at an identical phase forever and collide every cycle with anything
 * the device does on its own schedule.
 */
@Slf4j
@Component
public class InterfaceSnapshotPoller implements InterfaceSource {

    /**
     * Cap on distinct missing ifIndexes diagnosed per exporter per snapshot. A real device has far
     * fewer unresolvable interfaces than this; the cap exists because the key comes off the wire.
     */
    private static final int MAX_WARNED_MISSING_PER_EXPORTER = 64;

    /** One exporter's interface table as a single walk produced it. */
    private record Snapshot(Map<Integer, IfInfo> rows, long takenAtNanos) {
    }

    private static final class Registration {
        // not final: an inventory reload can repoint the range this was built from, and
        // the whole point of AD-6 is that the change reaches an agent already being polled
        private volatile SnmpEndpoint endpoint;
        private final AtomicBoolean walkInFlight = new AtomicBoolean();
        private volatile Snapshot snapshot;
        private volatile long lastSeenNanos;
        private volatile long nextWalkNanos;
        // AtomicInteger rather than a volatile int: only one walk per registration runs at a
        // time, but successive walks land on different pool threads, so the read-modify-write
        // needs to be atomic rather than merely visible
        private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean unreachable;
        /** Set when a refresh found this registration unpollable while a walk was running. */
        private volatile boolean stopWhenIdle;
        /**
         * Bumped whenever the endpoint is re-resolved. A walk that started before the
         * change must not write back a schedule derived from the endpoint it replaced.
         */
        private final java.util.concurrent.atomic.AtomicInteger resolution =
                new java.util.concurrent.atomic.AtomicInteger();
        /** Cleared whenever a new snapshot lands, so a diagnosis repeats at most once per walk. */
        private final Set<Integer> warnedMissing = ConcurrentHashMap.newKeySet();

        private Registration(final SnmpEndpoint endpoint, final long nowNanos) {
            this.endpoint = endpoint;
            this.lastSeenNanos = nowNanos;
            // zero delay: warmup is one walk rather than up to a full interval, and a mass
            // restart drains at pool width in first-flow order rather than bursting
            this.nextWalkNanos = nowNanos;
        }
    }

    private final SnmpService snmpService;
    private final SnmpPollConfig config;
    private final LongSupplier nanoTime;

    private final Map<InetSocketAddress, Registration> registrations = new ConcurrentHashMap<>();

    private final ExecutorService walkers;
    private final ScheduledExecutorService scheduler;

    private final Meter registered;
    private final Meter reresolved;
    private final Meter deregistered;
    /**
     * Counts <em>lookups</em> refused at the exporter bound, not distinct exporters: it is
     * marked on the trackAndResolve path, so a single rejected exporter contributes once per flow and
     * per direction. It is a pressure signal, not a population count, and cannot be used
     * directly to size {@code max-exporters}; compare it against the exporters gauge instead.
     */
    private final Meter rejected;

    @Autowired
    public InterfaceSnapshotPoller(final SnmpService snmpService,
                                   final SnmpPollConfig config,
                                   final MetricRegistry metrics) {
        this(snmpService, config, metrics, System::nanoTime, true);
    }

    /** Test seam: a controllable clock, and the option not to start the background scheduler. */
    // The ScheduledFuture is deliberately discarded: tickQuietly catches RuntimeException, so no
    // ordinary tick failure can cancel the schedule, and the executor is held in a field and shut
    // down with the component.
    //
    // This is narrower than it looks. An Error thrown from tick — an OutOfMemoryError under ingest
    // pressure being the realistic one — is not caught, would cancel the schedule, and would stop
    // interface polling for the process lifetime with nothing to observe it, since the discarded
    // handle is the only thing that would carry the failure. Tracked separately rather than
    // widened here; catching Error to keep a timer alive deserves its own decision.
    @SuppressWarnings("FutureReturnValueIgnored")
    InterfaceSnapshotPoller(final SnmpService snmpService,
                            final SnmpPollConfig config,
                            final MetricRegistry metrics,
                            final LongSupplier nanoTime,
                            final boolean startScheduler) {
        this.snmpService = Objects.requireNonNull(snmpService);
        this.config = Objects.requireNonNull(config);
        this.nanoTime = Objects.requireNonNull(nanoTime);

        // Fail loudly at startup rather than silently. Each of these otherwise disables SNMP
        // enrichment in a way that looks like a data problem: a non-positive pool width throws
        // from inside the executor factory naming neither property nor class, and non-positive
        // expiry or exporter bound make every trackAndResolve() return empty with only a meter to show
        // for it. Note 0 does not mean "unlimited" here, unlike the negative-cache TTL it
        // replaces, so check rather than reinterpreting.
        // cadence is no longer settable here (retired keys fail startup; profiles cut
        // over in a later story), so these two name the value, not a configurable key
        requirePositive(config.getRefreshIntervalMs(), "snmp poll refresh interval (built-in)");
        requirePositive(config.getSnapshotExpiryMs(), "snmp poll snapshot expiry (built-in)");
        requirePositive(config.getPoolWidth(), "riptide.snmp.poll.pool-width");
        requirePositive(config.getDeregisterAfter(), "riptide.snmp.poll.deregister-after");
        requirePositive(config.getDeadEndpointBaseMs(), "riptide.snmp.poll.dead-endpoint-base-ms");
        requirePositive(config.getDeadEndpointCeilingMs(), "riptide.snmp.poll.dead-endpoint-ceiling-ms");
        requirePositive(config.getMaxExporters(), "riptide.snmp.poll.max-exporters");
        if (config.getSnapshotExpiryMs() < config.getRefreshIntervalMs()) {
            // not fatal: it still works, it just throws away data it could have served
            log.warn("snmp poll snapshot expiry ({} ms) is shorter than the refresh interval ({} ms), "
                            + "so a snapshot expires before it is refreshed and enrichment will blank "
                            + "between walks",
                    config.getSnapshotExpiryMs(), config.getRefreshIntervalMs());
        }

        this.walkers = Executors.newFixedThreadPool(config.getPoolWidth(),
                runnable -> {
                    final Thread thread = new Thread(runnable, "snmp-walker");
                    thread.setDaemon(true);
                    return thread;
                });

        this.registered = metrics.meter(MetricRegistry.name("snmp", "poller", "registered"));
        this.reresolved = metrics.meter(MetricRegistry.name("snmp", "poller", "reresolved"));
        this.deregistered = metrics.meter(MetricRegistry.name("snmp", "poller", "deregistered"));
        this.rejected = metrics.meter(MetricRegistry.name("snmp", "poller", "rejectedLookups"));
        metrics.gauge(MetricRegistry.name("snmp", "poller", "exporters"), () -> this.registrations::size);
        metrics.gauge(MetricRegistry.name("snmp", "poller", "queueDepth"), () -> this::dueCount);
        metrics.gauge(MetricRegistry.name("snmp", "poller", "oldestSnapshotAgeMs"), () -> this::oldestSnapshotAgeMs);

        if (startScheduler) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "snmp-poll-scheduler");
                thread.setDaemon(true);
                return thread;
            });
            this.scheduler.scheduleWithFixedDelay(this::tickQuietly, 0, 1, TimeUnit.SECONDS);
        } else {
            this.scheduler = null;
        }
    }

    /**
     * Registers the exporter if this is its first flow, then answers from the snapshot.
     *
     * <p>Never issues SNMP. Between registration and the first completed walk there is no
     * snapshot and this returns empty, which is the warmup window: flows are still enriched from
     * static pins and exporter-pushed option data, just without SNMP-derived fields.
     */
    @Override
    public Optional<IfInfo> trackAndResolve(final SnmpEndpoint endpoint, final int ifIndex) {
        final long now = this.nanoTime.getAsLong();
        final Registration registration = register(endpoint, now);
        if (registration == null) {
            return Optional.empty();
        }
        registration.lastSeenNanos = now;

        final Snapshot snapshot = registration.snapshot;
        if (snapshot == null) {
            return Optional.empty();
        }
        // Served while stale but unexpired on purpose: an interface name from the previous cycle
        // beats none, and this is why refresh and expiry are separate settings.
        final long expiryMs = expiryMsFor(registration.endpoint);
        if (expiryMs <= 0 || now - snapshot.takenAtNanos() > millisToNanos(expiryMs)) {
            return Optional.empty();
        }

        final IfInfo ifInfo = snapshot.rows().get(ifIndex);
        if (ifInfo == null) {
            warnMissingOnce(registration, ifIndex);
        }
        return Optional.ofNullable(ifInfo);
    }

    /**
     * Diagnoses an ifIndex the agent does not carry, at most once per snapshot per ifIndex.
     *
     * <p>The old design discovered a miss by walking, so warning per lookup warned per walk.
     * Against a snapshot the absence is already known, so the same warning would fire on every
     * flow referencing that interface and scale with traffic while saying nothing new.
     *
     * <p>The set of already-warned indexes is bounded and cleared on each new snapshot. Bounded
     * because it is keyed by a value taken straight off the wire: without a cap, an exporter
     * spraying distinct ifIndexes would grow it without limit, which is the vector this change
     * closed elsewhere and must not reopen here.
     */
    private void warnMissingOnce(final Registration registration, final int ifIndex) {
        if (registration.warnedMissing.size() >= MAX_WARNED_MISSING_PER_EXPORTER) {
            return;
        }
        if (registration.warnedMissing.add(ifIndex)) {
            log.warn("Interface {} is not in the polled interface table of {}",
                    ifIndex, registration.endpoint);
        }
    }

    private Registration register(final SnmpEndpoint endpoint, final long now) {
        final InetSocketAddress address = endpoint.getInetSocketAddress();
        final Registration existing = this.registrations.get(address);
        if (existing != null) {
            // the fast path is where the capture bug lived: returning the registration and
            // dropping the endpoint the caller just resolved is what made a reload a no-op
            // for every agent already being polled
            reresolveIfChanged(existing, endpoint, now, address);
            return existing;
        }
        final int maxExporters = this.config.getMaxExporters();
        if (maxExporters <= 0 || this.registrations.size() >= maxExporters) {
            this.rejected.mark();
            return null;
        }
        final AtomicBoolean isNew = new AtomicBoolean(false);
        final Registration created =
                this.registrations.computeIfAbsent(address, key -> {
                    isNew.set(true);
                    return new Registration(endpoint, now);
                });
        if (isNew.get()) {
            if (this.registrations.size() > maxExporters) {
                this.registrations.remove(address, created);
                this.rejected.mark();
                return null;
            }
            this.registered.mark();
        } else {
            // lost the race to another thread's computeIfAbsent: same case as the fast path
            reresolveIfChanged(created, endpoint, now, address);
        }
        return created;
    }

    /**
     * Replaces a registration's endpoint when the caller resolved a different one for the
     * same address, which is how a reload reaches an agent that is already being polled:
     * credentials, port, timeout or cadence changed, and the next walk must use them.
     *
     * <p>An equal endpoint changes nothing, so the common case (every batch re-resolving
     * the same configuration) does not disturb the schedule.</p>
     */
    private void reresolveIfChanged(final Registration registration, final SnmpEndpoint endpoint,
                                    final long now, final InetSocketAddress address) {
        if (registration.endpoint.equals(endpoint)) {
            // adopt the instance anyway: this runs once per flow per direction, and the
            // enricher builds a fresh endpoint per batch, so leaving the old instance in
            // place means every flow pays a deep comparison instead of a reference check
            registration.endpoint = endpoint;
            return;
        }
        registration.endpoint = endpoint;
        // an operator who repoints a range is usually fixing something, so clear the
        // back-off and walk on the next tick rather than waiting out the ceiling
        registration.consecutiveFailures.set(0);
        registration.unreachable = false;
        registration.nextWalkNanos = now;
        registration.resolution.incrementAndGet();
        this.reresolved.mark();
        log.info("Endpoint for {} re-resolved from the current inventory; walking on the next tick", address);
    }

    /**
     * Test seam. Walks complete on the pool after {@link #tick} returns, and a registration's next
     * walk time is only set once the walk finishes — so a test that merely counted issued walks
     * would race the bookkeeping and see a stale schedule.
     */
    boolean anyWalkInFlight() {
        return this.registrations.values().stream().anyMatch(r -> r.walkInFlight.get());
    }

    /**
     * Re-resolves every registration against a freshly published inventory, which is the
     * refresh half of swap-then-refresh (AD-6): the reloader commits the snapshot and then
     * calls this, so ordering lives with the component that owns the swap rather than
     * being inferred here.
     *
     * <p>A registration whose address no longer resolves to a pollable range is
     * deregistered rather than left walking. That is what makes {@code enabled: false},
     * a deleted range, or a removed credential reference take effect on reload instead of
     * waiting out the deregistration deadline: without it an operator can carve a segment
     * out, see the reload succeed, and still be polling it minutes later.</p>
     *
     * <p>Note what this does <em>not</em> reach: the value of a credential set. Ranges are
     * re-resolved against the published snapshot, but that snapshot resolved its
     * {@code CredentialSet} objects from the profile beans as they were bound at boot, and
     * a main-config reload deliberately discards its credential bind. Rotating a community
     * therefore still needs a restart, which is the orchestration gap the endpoint-refresh
     * work leaves open.</p>
     *
     * <p>Agent ranges carry no observation-domain pin, so resolving by address alone is
     * exact rather than approximate; {@code agentRangesResolveRegardlessOfObservationDomain}
     * pins that, and it is the assumption to revisit first if pinning is ever added.</p>
     */
    public void refreshRegistrations(final InventorySnapshot snapshot) {
        final long now = this.nanoTime.getAsLong();
        for (final Map.Entry<InetSocketAddress, Registration> entry : this.registrations.entrySet()) {
            final InetSocketAddress address = entry.getKey();
            final Registration registration = entry.getValue();
            final Optional<SnmpEndpoint> resolved = resolve(snapshot, address);

            if (resolved.isEmpty() || !resolved.get().getInetSocketAddress().equals(address)) {
                // gone, carved out, uncredentialed, or moved to another port: stop walking
                // it. A port change re-registers under its own key on the next flow
                if (registration.walkInFlight.get()) {
                    // removing it now would let a re-registration mint a fresh in-flight
                    // flag and start a second concurrent walk. Marking it means the next
                    // tick finishes the job rather than the carve-out waiting out the
                    // deregistration deadline, which is what it would do if we just skipped
                    registration.stopWhenIdle = true;
                    continue;
                }
                if (this.registrations.remove(address, registration)) {
                    // only when this call is the one that removed it: tick's silence path
                    // races here, and marking both would make the meter unreconcilable
                    // against the exporters gauge
                    this.deregistered.mark();
                    log.info("Stopped polling {}: it no longer resolves to a pollable agent range", address);
                }
                continue;
            }
            reresolveIfChanged(registration, resolved.get(), now, address);
        }
    }

    private static Optional<SnmpEndpoint> resolve(final InventorySnapshot snapshot, final InetSocketAddress address) {
        final ExporterIdentity identity = new ExporterIdentity.NetflowIpfix(address.getAddress(), 0L);
        return snapshot.agentView().match(identity)
                .flatMap(agent -> AgentEndpointFactory.endpointFor(
                        agent, new IPAddressString(address.getAddress().getHostAddress())));
    }

    /** Package-private so tests can advance the schedule without waiting on wall-clock time. */
    // registrations is a ConcurrentHashMap, whose iterators are explicitly weakly consistent and
    // documented to tolerate concurrent removal — including by the iterating thread. The check
    // fires on the shape of the loop, not on the collection's actual contract.
    @SuppressWarnings("ModifyCollectionInEnhancedForLoop")
    void tick(final long now) {
        final long deregisterAfter = Math.max(1, (long) this.config.getDeregisterAfter());

        for (final Map.Entry<InetSocketAddress, Registration> entry : this.registrations.entrySet()) {
            final Registration registration = entry.getValue();
            if (registration.stopWhenIdle && !registration.walkInFlight.get()) {
                if (this.registrations.remove(entry.getKey(), registration)) {
                    this.deregistered.mark();
                    log.info("Stopped polling {}: it no longer resolves to a pollable agent range", entry.getKey());
                }
                continue;
            }
            // silence is measured in the registration's own refresh intervals, so a slow
            // profile is not deregistered for missing a fast profile's deadline
            final long refreshMs = Math.max(1, refreshMsFor(registration.endpoint));
            long timeoutNanos;
            try {
                timeoutNanos = Math.multiplyExact(millisToNanos(refreshMs), deregisterAfter);
            } catch (final ArithmeticException e) {
                timeoutNanos = Long.MAX_VALUE;
            }

            if (now - registration.lastSeenNanos > timeoutNanos) {
                // Not while a walk is running: the in-flight flag lives on this object, so
                // removing it lets a re-registration mint a fresh flag and start a second
                // concurrent walk against an agent whose first walk is still parked in its
                // timeout. Deregistration can wait a tick.
                if (!registration.walkInFlight.get()) {
                    this.registrations.remove(entry.getKey(), registration);
                    this.deregistered.mark();
                }
                continue;
            }
            if (now < registration.nextWalkNanos) {
                continue;
            }
            // one queue entry per endpoint, so a walk still running is never joined by a second
            if (!registration.walkInFlight.compareAndSet(false, true)) {
                continue;
            }
            try {
                this.walkers.execute(() -> walk(registration));
            } catch (final Exception e) {
                registration.walkInFlight.set(false);
                log.warn("Failed to submit SNMP walk task for endpoint {}: {}", registration.endpoint, e.getMessage());
            }
        }
    }

    private void tickQuietly() {
        try {
            tick(this.nanoTime.getAsLong());
        } catch (final RuntimeException e) {
            // a throwing task would cancel the schedule and stop all polling for the process
            // lifetime, which is the failure this catch exists to prevent
            log.warn("Interface poll tick failed", e);
        }
    }

    private void walk(final Registration registration) {
        // captured before the walk: a re-resolution landing mid-walk asks for an immediate
        // re-walk on the new endpoint, and writing this walk's schedule back afterwards
        // would silently defer the new credentials by a whole interval
        final int resolution = registration.resolution.get();
        try {
            final SnmpService.InterfaceTable table = this.snmpService.walkInterfaces(registration.endpoint);
            final long now = this.nanoTime.getAsLong();

            if (table.walkFailed()) {
                backOff(registration, now);
                if (!registration.unreachable) {
                    registration.unreachable = true;
                    // transitions are logged, not attempts: a per-retry warning would scale with
                    // how long a device stays down
                    log.warn("SNMP endpoint {} did not answer usably, backing off", registration.endpoint);
                }
                return;
            }

            if (registration.unreachable) {
                registration.unreachable = false;
                log.info("SNMP endpoint {} answers again", registration.endpoint);
            }
            registration.consecutiveFailures.set(0);
            final Map<Integer, IfInfo> rows = table.rows() != null ? table.rows() : Map.of();
            registration.snapshot = new Snapshot(Map.copyOf(rows), now);
            registration.warnedMissing.clear();
            if (registration.resolution.get() == resolution) {
                registration.nextWalkNanos = nextWalkAt(registration.endpoint, now);
            }
        } catch (final RuntimeException e) {
            // Without this the schedule is never advanced for a registration whose walk throws
            // something the SNMP layer does not degrade — an snmp4j target construction failure,
            // say — and the 1 Hz scheduler would re-submit that endpoint every second forever,
            // with a stack trace each time. Back off exactly as a failed walk does, so an
            // endpoint that throws is treated no more kindly than one that does not answer.
            backOff(registration, this.nanoTime.getAsLong());
            if (!registration.unreachable) {
                registration.unreachable = true;
                log.warn("Interface walk of {} failed unexpectedly, backing off", registration.endpoint, e);
            }
        } finally {
            registration.walkInFlight.set(false);
        }
    }

    private void backOff(final Registration registration, final long now) {
        registration.nextWalkNanos = now + backoffNanos(registration.consecutiveFailures.incrementAndGet());
    }

    /**
     * Doubling back-off to a ceiling. Load-bearing rather than cosmetic: a walk against an
     * unreachable agent holds a pool slot for its whole timeout, so retrying at a fixed interval
     * lets a population of dead exporters starve the live ones of slots.
     */
    private long backoffNanos(final int consecutiveFailures) {
        final long base = Math.max(1L, this.config.getDeadEndpointBaseMs());
        final long ceiling = Math.max(base, this.config.getDeadEndpointCeilingMs());
        final int doublings = Math.max(0, Math.min(consecutiveFailures - 1, 30));
        long delay = base;
        for (int i = 0; i < doublings && delay < ceiling; i++) {
            // Test against half the ceiling rather than doubling first and clamping after. The
            // latter needs an overflow check that can never fire (the ceiling is reached first),
            // so it reads as a guard while being dead code. This keeps delay within
            // [base, ceiling] by construction, so it can never go negative, which would
            // schedule the next walk in the past and busy-loop the endpoint being backed off.
            delay = delay > ceiling / 2 ? ceiling : delay * 2;
        }
        return millisToNanos(delay);
    }

    /**
     * The next time this endpoint should be walked, aligned to its own phase within the interval.
     *
     * <p>Aligning to a phase rather than simply adding the interval is what actually spreads the
     * fleet. First walks run immediately on registration, so a mass restart registers every
     * exporter at once; if the next walk were just {@code now + interval} they would stay in the
     * lockstep they started in and re-walk together forever — the same synchronized herd the
     * demand-filled design produced when every cache entry expired at once.
     *
     * <p>The phase comes from the endpoint address, so it is stable across restarts and needs no
     * stored state. It distributes only statistically and collisions are expected; the walker pool
     * absorbs those as a brief queue rather than a burst at any one agent.
     *
     * <p>A small jitter on top serves a different purpose: a perfectly fixed phase would poll a
     * device at the same moment in every cycle and collide repeatedly with anything the device does
     * on its own schedule.
     */
    private long nextWalkAt(final SnmpEndpoint endpoint, final long now) {
        final long intervalMs = refreshMsFor(endpoint);
        if (intervalMs <= 0) {
            return now + millisToNanos(60_000);
        }
        final long interval = millisToNanos(intervalMs);
        // Mixed into the full 64-bit range before reducing. Taking the address hash modulo the
        // interval directly does not work: the hash is at most 2^32, about 4.3e9, while a 600 s
        // interval is 6e11 nanoseconds — so every phase would land in the first four seconds and
        // the fleet would not spread at all.
        final long mixed = mix(endpoint.getInetSocketAddress().hashCode());
        final long phase = Math.floorMod(mixed, interval);

        long next = now - Math.floorMod(now - phase, interval) + interval;
        final long jitter = interval / 50;
        if (jitter > 0) {
            next += Math.floorMod(mix(mixed), jitter);
        }
        return next;
    }

    private long dueCount() {
        final long now = this.nanoTime.getAsLong();
        return this.registrations.values().stream().filter(r -> now >= r.nextWalkNanos).count();
    }

    private long oldestSnapshotAgeMs() {
        final long now = this.nanoTime.getAsLong();
        return this.registrations.values().stream()
                .map(r -> r.snapshot)
                .filter(Objects::nonNull)
                .map(s -> TimeUnit.NANOSECONDS.toMillis(now - s.takenAtNanos()))
                .max(Comparator.naturalOrder())
                .orElse(0L);
    }

    /**
     * Configuration hot-reload: endpoints or credentials may have changed, so everything is due
     * for a fresh walk immediately and any accumulated back-off is abandoned.
     *
     * <p>Snapshots are deliberately <em>kept</em> rather than dropped. Under the demand-filled
     * design clearing was free because the next flow refilled synchronously; here a cleared
     * snapshot is a real gap, and every reload — including one that changed nothing about SNMP —
     * would blank interface names for the whole fleet until each exporter was re-walked. The
     * existing data is served, staleness-bounded as always, while the re-walk happens underneath.
     *
     * <p>Registrations are kept for a second reason: dropping one whose walk is still in flight
     * would let a re-registration create a fresh in-flight flag and start a concurrent walk
     * against the same agent, breaking the one-walk-per-endpoint guarantee.
     */
    public void invalidateAll() {
        final long now = this.nanoTime.getAsLong();
        for (final Registration registration : this.registrations.values()) {
            registration.consecutiveFailures.set(0);
            registration.unreachable = false;
            registration.nextWalkNanos = now;
        }
    }

    @PreDestroy
    void stop() {
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
        }
        this.walkers.shutdownNow();
        try {
            if (!this.walkers.awaitTermination(3, TimeUnit.SECONDS)) {
                log.debug("Walker pool did not terminate within 3 seconds");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void requirePositive(final long value, final String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be greater than 0, but was " + value);
        }
    }

    /** splitmix64 finalizer: spreads a small, poorly distributed hash across the full long range. */
    private static long mix(final long value) {
        long z = value * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The endpoint's own profile cadence, or the fleet setting for a legacy endpoint. */
    private long refreshMsFor(final SnmpEndpoint endpoint) {
        return endpoint.getRefreshInterval() == null
                ? this.config.getRefreshIntervalMs()
                : endpoint.getRefreshInterval().toMillis();
    }

    private long expiryMsFor(final SnmpEndpoint endpoint) {
        return endpoint.getSnapshotExpiry() == null
                ? this.config.getSnapshotExpiryMs()
                : endpoint.getSnapshotExpiry().toMillis();
    }

    private static long millisToNanos(final long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
