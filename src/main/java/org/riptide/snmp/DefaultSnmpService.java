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
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class DefaultSnmpService implements SnmpService {

    private final SecretResolvers secretResolvers;
    private final Map<SnmpVersion, Snmp> sessions = new EnumMap<>(SnmpVersion.class);
    private final Map<SnmpVersion, SnmpBuilder> builders = new EnumMap<>(SnmpVersion.class);

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
    public InterfaceTable walkInterfaces(final SnmpEndpoint snmpEndpoint) {
        this.walks.mark();
        try (var ignored = this.walkDuration.time()) {
            final var walk = SnmpUtils.getIfInfoMap(snmpEndpoint, this.secretResolvers);
            switch (walk.outcome()) {
                case OK -> this.walksSucceeded.mark();
                case TIMEOUT -> this.walksTimedOut.mark();
                case ERROR -> this.walksFailed.mark();
                case ABANDONED -> this.walksAbandoned.mark();
            }
            // any non-OK outcome is a failed walk: an error PDU is no more worth retrying
            // immediately than a timeout, and the meters above keep the distinction
            return new InterfaceTable(walk.rows(), walk.outcome() != SnmpUtils.WalkOutcome.OK);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: an unresolvable secret reference must degrade to an
            // unenriched flow, never fail the pipeline and drop the batch.
            this.walksFailed.mark();
            log.warn("Error walking the interface table of {}: {}", snmpEndpoint, e.getMessage());
            return new InterfaceTable(Map.of(), true);
        }
    }

    /**
     * One session per version, built on first use. Closing the service closes them all. The
     * {@code SnmpBuilder} is kept alongside the session because {@link SnmpVersion#getTarget}
     * configures the target on the already-built builder, which is why every builder sets
     * {@code allowIncrementalConfigAfterBuild()}.
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
    public CollectedTable collect(final SnmpEndpoint endpoint, final CollectionDefinition definition,
                                  final Duration budget) {
        this.collects.mark();
        final long deadline = System.nanoTime() + budget.toNanos();
        try (var ignored = this.collectDuration.time()) {
            final SnmpVersion version = endpoint.getSnmpDefinition().getSnmpVersion();
            final Snmp snmp = session(version);
            final Target<?> target = version.getTarget(snmp, this.builders.get(version), endpoint, this.secretResolvers);
            final CollectedTable table = SnmpUtils.collect(snmp, target, endpoint, definition, deadline);
            if (table.walkFailed()) {
                this.collectsFailed.mark();
            }
            return table;
        } catch (IOException | IllegalArgumentException e) {
            this.collectsFailed.mark();
            log.warn("SNMP collect against {} failed: {}", endpoint, e.getMessage());
            return new CollectedTable(Map.of(), true);
        }
    }

    /** Test seam. */
    synchronized int openSessions() {
        return this.sessions.size();
    }

    @PreDestroy
    public synchronized void close() {
        for (final Snmp snmp : this.sessions.values()) {
            try {
                snmp.close();
            } catch (final IOException e) {
                log.debug("Closing SNMP session: {}", e.getMessage());
            }
        }
        this.sessions.clear();
    }
}
