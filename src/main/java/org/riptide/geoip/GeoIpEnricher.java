/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.geoip;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.EnricherOrder;
import org.riptide.pipeline.Source;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Fills geo fields (country/city for source and destination) and — as the ladder's lowest
 * rung — AS data from mmdb databases and manual overrides. Runs after {@link
 * org.riptide.routing.RoutingEnricher}: an exporter-provided or routing-mapped AS number always
 * wins, GeoIP only fills what is still zero/absent. Without any configured database or override
 * this enricher is a no-op.
 */
@Component
@Order(EnricherOrder.GEOIP)
@ConditionalOnProperty(name = "riptide.enricher.geoip.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class GeoIpEnricher extends Enricher.Single {

    /** Delay before closing a replaced snapshot's readers — lets in-flight lookups drain. */
    private static final long CLOSE_DELAY_SECONDS = 30;

    @NonNull
    private final GeoIpConfig config;

    private volatile GeoIpSnapshot snapshot = GeoIpSnapshot.EMPTY;

    /** Disk state of a reload that failed to fully open — retried only once it changes again. */
    private Map<String, Long> lastFailedFingerprint;

    /** Replaced snapshots awaiting their delayed close — closed eagerly on stop(). */
    private final List<GeoIpSnapshot> retiring = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;

    @Override
    public void start() {
        if (this.config.isUnconfigured()) {
            return;
        }
        this.snapshot = GeoIpSnapshot.open(this.config.getDatabases(), this.config.parsedOverrides());
        final long period = this.config.getRefreshInterval().toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "geoip-refresh");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::refresh, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (this.scheduler != null) {
            // Fence the refresh: an in-flight run may still swap in a fresh snapshot, so wait
            // for termination before tearing down state — otherwise that snapshot leaks open.
            this.scheduler.shutdownNow();
            try {
                this.scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Delayed closes dropped by shutdownNow() are closed eagerly here.
        this.retiring.forEach(GeoIpSnapshot::close);
        this.retiring.clear();
        this.snapshot.close();
        this.snapshot = GeoIpSnapshot.EMPTY;
    }

    /** Re-opens the databases when a file changed, appeared, or vanished; swap is atomic. */
    void refresh() {
        try {
            final GeoIpSnapshot current = this.snapshot;
            final Map<String, Long> disk = GeoIpSnapshot.fingerprintOf(this.config.getDatabases());
            if (disk.equals(current.fingerprint()) || disk.equals(this.lastFailedFingerprint)) {
                return; // unchanged, or a known-bad state that was already warned about
            }
            log.info("GeoIP databases changed on disk — reloading");
            final GeoIpSnapshot next = GeoIpSnapshot.open(this.config.getDatabases(), this.config.parsedOverrides());
            if (next.openFailures() > 0 && !current.isEmpty()) {
                // A file that fails to open leaves the previous snapshot serving.
                log.warn("GeoIP reload found unreadable database(s) — keeping the previous databases");
                this.lastFailedFingerprint = disk;
                next.close();
                return;
            }
            this.lastFailedFingerprint = null;
            this.snapshot = next;
            if (this.scheduler != null) {
                this.retiring.add(current);
                this.scheduler.schedule(() -> {
                    current.close();
                    this.retiring.remove(current);
                }, CLOSE_DELAY_SECONDS, TimeUnit.SECONDS);
            } else {
                current.close();
            }
        } catch (final RuntimeException e) {
            // Serving state is only ever replaced by a successfully opened snapshot.
            log.warn("GeoIP refresh failed — keeping the previous databases: {}", e.getMessage());
        }
    }

    @Override
    protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
        final GeoIpSnapshot snap = this.snapshot;
        if (!snap.isEmpty()) {
            enrichSide(snap, flow.getSrcAddr(), flow::setSrcCountry, flow::setSrcCity,
                    flow::getSrcAs, flow::setSrcAs, flow::getSrcAsOrg, flow::setSrcAsOrg);
            enrichSide(snap, flow.getDstAddr(), flow::setDstCountry, flow::setDstCity,
                    flow::getDstAs, flow::setDstAs, flow::getDstAsOrg, flow::setDstAsOrg);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void enrichSide(final GeoIpSnapshot snap, final InetAddress address,
                            final Consumer<String> setCountry, final Consumer<String> setCity,
                            final Supplier<Long> getAs, final Consumer<Long> setAs,
                            final Supplier<String> getOrg, final Consumer<String> setOrg) {
        if (address == null) {
            return;
        }
        final GeoInfo info = snap.lookup(address);
        if (info.isEmpty()) {
            return;
        }
        if (info.country() != null) {
            setCountry.accept(info.country());
        }
        if (info.city() != null) {
            setCity.accept(info.city());
        }
        // AS data is the ladder's lowest rung: fill only zeros/absent, and only name an AS
        // number that actually is the GeoIP one — never label an exporter- or routing-provided
        // number with a GeoIP org.
        final Long current = getAs.get();
        if ((current == null || current == 0) && info.asn() != null) {
            setAs.accept(info.asn());
        }
        if (getOrg.get() == null && info.asOrg() != null && Objects.equals(getAs.get(), info.asn())) {
            setOrg.accept(info.asOrg());
        }
    }
}
