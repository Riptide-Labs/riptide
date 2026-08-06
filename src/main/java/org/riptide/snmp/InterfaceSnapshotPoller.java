/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.Meter;
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
 *   <li>Enrichment never walks. {@link #resolve} only reads, so no parser thread can block on SNMP.</li>
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

    /** One exporter's interface table as a single walk produced it. */
    private record Snapshot(Map<Integer, IfInfo> rows, long takenAtNanos) {
    }

    private static final class Registration {
        private final SnmpEndpoint endpoint;
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
    private final Meter deregistered;
    private final Meter rejected;

    @Autowired
    public InterfaceSnapshotPoller(final SnmpService snmpService,
                                   final SnmpPollConfig config,
                                   final MetricRegistry metrics) {
        this(snmpService, config, metrics, System::nanoTime, true);
    }

    /** Test seam: a controllable clock, and the option not to start the background scheduler. */
    InterfaceSnapshotPoller(final SnmpService snmpService,
                            final SnmpPollConfig config,
                            final MetricRegistry metrics,
                            final LongSupplier nanoTime,
                            final boolean startScheduler) {
        this.snmpService = Objects.requireNonNull(snmpService);
        this.config = Objects.requireNonNull(config);
        this.nanoTime = Objects.requireNonNull(nanoTime);

        this.walkers = Executors.newFixedThreadPool(config.getPoolWidth(),
                runnable -> {
                    final Thread thread = new Thread(runnable, "snmp-walker");
                    thread.setDaemon(true);
                    return thread;
                });

        this.registered = metrics.meter(MetricRegistry.name("snmp", "poller", "registered"));
        this.deregistered = metrics.meter(MetricRegistry.name("snmp", "poller", "deregistered"));
        this.rejected = metrics.meter(MetricRegistry.name("snmp", "poller", "rejectedAtBound"));
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
    public Optional<IfInfo> resolve(final SnmpEndpoint endpoint, final int ifIndex) {
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
        if (now - snapshot.takenAtNanos() > millisToNanos(this.config.getSnapshotExpiryMs())) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.rows().get(ifIndex));
    }

    private Registration register(final SnmpEndpoint endpoint, final long now) {
        final InetSocketAddress address = endpoint.getInetSocketAddress();
        final Registration existing = this.registrations.get(address);
        if (existing != null) {
            return existing;
        }
        // Registration is driven by flow arrival, so the population follows whatever addresses
        // send flows — including spoofed ones. Bound in exporters, not in interface entries.
        if (this.registrations.size() >= this.config.getMaxExporters()) {
            this.rejected.mark();
            return null;
        }
        final Registration created =
                this.registrations.computeIfAbsent(address, key -> new Registration(endpoint, now));
        if (created.lastSeenNanos == now) {
            this.registered.mark();
        }
        return created;
    }

    /**
     * Test seam. Walks complete on the pool after {@link #tick} returns, and a registration's next
     * walk time is only set once the walk finishes — so a test that merely counted issued walks
     * would race the bookkeeping and see a stale schedule.
     */
    boolean anyWalkInFlight() {
        return this.registrations.values().stream().anyMatch(r -> r.walkInFlight.get());
    }

    /** Package-private so tests can advance the schedule without waiting on wall-clock time. */
    void tick(final long now) {
        for (final Map.Entry<InetSocketAddress, Registration> entry : this.registrations.entrySet()) {
            final Registration registration = entry.getValue();

            if (now - registration.lastSeenNanos
                    > millisToNanos(this.config.getRefreshIntervalMs()) * this.config.getDeregisterAfter()) {
                this.registrations.remove(entry.getKey(), registration);
                this.deregistered.mark();
                continue;
            }
            if (now < registration.nextWalkNanos) {
                continue;
            }
            // one queue entry per endpoint, so a walk still running is never joined by a second
            if (!registration.walkInFlight.compareAndSet(false, true)) {
                continue;
            }
            this.walkers.execute(() -> walk(registration));
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
        try {
            final SnmpService.InterfaceTable table = this.snmpService.walkInterfaces(registration.endpoint);
            final long now = this.nanoTime.getAsLong();

            if (table.endpointTimedOut()) {
                registration.nextWalkNanos = now + backoffNanos(registration.consecutiveFailures.incrementAndGet());
                if (!registration.unreachable) {
                    registration.unreachable = true;
                    // transitions are logged, not attempts: a per-retry warning would scale with
                    // how long a device stays down
                    log.warn("SNMP endpoint {} does not answer, backing off", registration.endpoint);
                }
                return;
            }

            if (registration.unreachable) {
                registration.unreachable = false;
                log.info("SNMP endpoint {} answers again", registration.endpoint);
            }
            registration.consecutiveFailures.set(0);
            registration.snapshot = new Snapshot(Map.copyOf(table.rows()), now);
            registration.nextWalkNanos = nextWalkAt(registration.endpoint, now);
        } finally {
            registration.walkInFlight.set(false);
        }
    }

    /**
     * Doubling back-off to a ceiling. Load-bearing rather than cosmetic: a walk against an
     * unreachable agent holds a pool slot for its whole timeout, so retrying at a fixed interval
     * lets a population of dead exporters starve the live ones of slots.
     */
    private long backoffNanos(final int consecutiveFailures) {
        final long base = this.config.getDeadEndpointBaseMs();
        final long ceiling = this.config.getDeadEndpointCeilingMs();
        final int doublings = Math.min(consecutiveFailures - 1, 32);
        final long delay = base >= ceiling >> doublings ? ceiling : base << doublings;
        return millisToNanos(Math.min(delay, ceiling));
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
        final long interval = millisToNanos(this.config.getRefreshIntervalMs());
        if (interval <= 0) {
            return now;
        }
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

    /** Configuration hot-reload: endpoints or credentials may have changed. */
    public void invalidateAll() {
        // clears the accumulated back-off as well as the snapshots, so the next tick retries a
        // previously unreachable endpoint immediately rather than at its escalated delay
        this.registrations.clear();
    }

    @PreDestroy
    void stop() {
        if (this.scheduler != null) {
            this.scheduler.shutdownNow();
        }
        this.walkers.shutdownNow();
    }

    /** splitmix64 finalizer: spreads a small, poorly distributed hash across the full long range. */
    private static long mix(final long value) {
        long z = value * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long millisToNanos(final long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
