/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import lombok.extern.slf4j.Slf4j;
import org.riptide.secrets.SecretResolvers;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
public class DefaultSnmpService implements SnmpService {

    private final SecretResolvers secretResolvers;

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
    private final Meter walksFailed;

    public DefaultSnmpService(final SecretResolvers secretResolvers, final MetricRegistry metrics) {
        this.secretResolvers = Objects.requireNonNull(secretResolvers);
        Objects.requireNonNull(metrics);
        this.walks = metrics.meter(MetricRegistry.name("snmp", "walks"));
        this.walkDuration = metrics.timer(MetricRegistry.name("snmp", "walkDuration"));
        this.walksSucceeded = metrics.meter(MetricRegistry.name("snmp", "walks", "succeeded"));
        this.walksTimedOut = metrics.meter(MetricRegistry.name("snmp", "walks", "timedOut"));
        this.walksFailed = metrics.meter(MetricRegistry.name("snmp", "walks", "failed"));
    }

    @Override
    public Optional<IfInfo> getIfInfo(final SnmpEndpoint snmpEndpoint, final int ifIndex) {
        return lookupIfInfo(snmpEndpoint, ifIndex).ifInfo();
    }

    /**
     * Resolves one interface by walking the whole table and keeping a single row.
     *
     * <p>Retained for the demand-filled caching layer. Every caller that needs more than one
     * interface from the same exporter should use {@link #walkInterfaces} instead, because
     * this discards the other rows the walk already paid for.
     */
    @Override
    public IfInfoLookup lookupIfInfo(final SnmpEndpoint snmpEndpoint, final int ifIndex) {
        final InterfaceTable table = walkInterfaces(snmpEndpoint);
        return new IfInfoLookup(Optional.ofNullable(table.rows().get(ifIndex)), table.endpointTimedOut());
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
            }
            return new InterfaceTable(walk.rows(), walk.outcome() != SnmpUtils.WalkOutcome.OK);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: an unresolvable secret reference must degrade to an
            // unenriched flow, never fail the pipeline and drop the batch.
            this.walksFailed.mark();
            log.warn("Error walking the interface table of {}: {}", snmpEndpoint, e.getMessage());
            return new InterfaceTable(Map.of(), true);
        }
    }
}
