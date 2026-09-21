/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounds on the session state riptide retains for exporters it has never authenticated.
 *
 * <p>Every table these limits govern is keyed, directly or indirectly, on the exporter
 * <em>scope identity</em> — {@code (sourceAddress, observationDomainId)} for NetFlow v9/IPFIX,
 * {@code (agentAddress, subAgentId)} for sFlow. Both halves of the sFlow pair come out of the
 * datagram payload rather than the UDP header, so one un-spoofed sender can mint scope identities
 * across the whole agent-address space. The observation domain is a 32-bit field the sender picks
 * freely. Without a bound, a single source varying one header field grows the tables until the heap
 * is gone.
 *
 * <p>The point of these three numbers is that an operator can multiply them out. Worst-case
 * retained state is a product of documented limits rather than a function of what arrives:
 *
 * <pre>
 *   session tables    : maxSources x maxScopesPerSource x ~852 B
 *   interface table   : maxSources x maxScopesPerSource x maxIfIndexesPerScope x ~144 B
 *   application table : maxSources x maxScopesPerSource x 16,384 x ~464 B
 * </pre>
 *
 * <p>The application table's per-scope cap is a fixed 16,384 and not one of these properties. Its
 * entry is the interface entry's ~144 B, plus a name of at most 64 characters and a description of
 * at most 255.
 *
 * <p>Read the last two lines as a ceiling, not an expectation. Reaching either means holding every
 * one of {@code maxSources} slots at once. What a single source can actually spend is
 * {@code maxScopesPerSource x maxIfIndexesPerScope x ~144 B} for interfaces and
 * {@code maxScopesPerSource x 16,384 x ~464 B} for applications. That is about 2.4 MB and about
 * 122 MB at the defaults below, so about 124 MB together. A real fleet holds one scope per exporter
 * with its own interfaces and one protocol pack, orders of magnitude below any of these figures.
 *
 * <p>JavaBean properties (not bare public fields) on purpose, for the reason recorded in
 * {@code SnmpCacheConfig}: Spring's binder silently skips fields without accessors, which here
 * would leave every bound at 0 and reject all traffic.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "riptide.flows.session")
public class SessionAdmissionConfig {

    /**
     * Distinct UDP sources for which session state is retained.
     *
     * <p>Aligned with {@code riptide.snmp.poll.max-exporters} so the two admission decisions on the
     * ingest path agree about how large a fleet is plausible. Reaching this bound rejects
     * <em>new</em> sources and leaves admitted ones alone: the alternative, evicting the
     * least-recently-used source, would let a flood choose which real exporters stop being
     * monitored.
     */
    private int maxSources = 4_096;

    /**
     * Scope identities admitted per source, evicted least-recently-used within that source.
     *
     * <p>LRU is safe here and unsafe globally. Confined to one source's own budget, the blast radius
     * of a spray is that source's own state; an attacker cannot reach across and evict a different
     * exporter's templates. It also forces a spoofing attacker to <em>sustain</em> traffic on every
     * forged address to hold its slots, which is what removes the fire-and-forget property.
     *
     * <p>A per-linecard chassis legitimately exports several observation domains from one address,
     * so this cannot be 1. Sixteen is generous for that shape while still bounding a spray to a
     * fixed multiple of the source count.
     */
    private int maxScopesPerSource = 16;

    /**
     * Interface entries retained per scope identity in the exporter option table.
     *
     * <p>A per-scope cap alone is not enough for that table: option <em>data records</em> arrive
     * several hundred to a datagram, and an attacker inside a single admitted scope can spray
     * {@code ifIndex} across 2^32 values. This bounds the inner map so the product above stays
     * finite.
     *
     * <p>Sized for real hardware rather than for the worst-case product. A large chassis router
     * carries hundreds to low thousands of interfaces once subinterfaces are counted, and every one
     * is a legitimate option record in a single scope — so a tight cap here does not inconvenience
     * an attacker, it discards real interface names on healthy deployments. An earlier value of 128
     * evicted 372 of a 500-interface router's interfaces.
     *
     * <p>The reason a generous value is affordable: what an attacker can actually spend is bounded
     * per source, at {@code maxScopesPerSource x maxIfIndexesPerScope x ~144 B} — about 2.4 MB from
     * one address at these defaults. Reaching the full product in the class comment additionally
     * requires occupying every one of {@code maxSources} slots, which needs that many distinct
     * source addresses and, because the source table refuses new entrants rather than evicting,
     * winning them ahead of the real fleet. The source bound is the control that matters there.
     *
     * <p>Eviction is least-recently-used within the scope, so a device carrying more interfaces than
     * this keeps the ones actually referenced by traffic and loses the idle tail. Rejection only
     * degrades — static pins and live SNMP still resolve the interface, and the flow is still
     * emitted — which is why this budget is the quietest of the three.
     */
    private int maxIfIndexesPerScope = 1_024;

    /**
     * How long an admitted source may go unheard before its budget is released.
     *
     * <p>Matches the default template timeout, so admission state does not outlive the state it
     * governs. It is what makes the bound recover on its own after a flood stops rather than
     * staying full until restart.
     */
    private Duration sourceIdleTimeout = Duration.ofMinutes(30);

    /**
     * Reject a configuration that would disable the bound instead of setting it.
     *
     * <p>Called from every component these limits govern, because a non-positive value here is not
     * a smaller bound — it is no bound, or no service. {@code max-scopes-per-source} at zero would
     * leave the tables growing exactly as they did before this change, restoring the vulnerability
     * silently; {@code max-sources} at zero would refuse every exporter and stop NetFlow v9 and
     * IPFIX decoding outright. Neither should be discoverable only from a metric.
     *
     * <p>Same posture, and the same message shape, as {@code InterfaceSnapshotPoller}'s checks on
     * its own intervals: fail at startup naming the property, rather than at runtime naming nothing.
     */
    public void validate() {
        requirePositive(this.maxSources, "riptide.flows.session.max-sources");
        requirePositive(this.maxScopesPerSource, "riptide.flows.session.max-scopes-per-source");
        requirePositive(this.maxIfIndexesPerScope, "riptide.flows.session.max-ifindexes-per-scope");
        if (this.sourceIdleTimeout == null || this.sourceIdleTimeout.isNegative()
                || this.sourceIdleTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "riptide.flows.session.source-idle-timeout must be positive, but was "
                            + this.sourceIdleTimeout
                            + " — a non-positive timeout reclaims every source on the next sweep, so no"
                            + " exporter would keep its admission slot between packets.");
        }
    }

    private static void requirePositive(final int value, final String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive, but was " + value
                    + " — zero or negative disables the bound rather than tightening it.");
        }
    }
}
