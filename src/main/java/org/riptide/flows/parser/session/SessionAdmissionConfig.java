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
 *   session tables : maxSources x maxScopesPerSource x ~852 B
 *   option table   : maxSources x maxScopesPerSource x maxIfIndexesPerScope x ~144 B
 * </pre>
 *
 * <p>At the defaults below that is roughly 56 MB of session state and 600 MB of option-table
 * entries — the latter being a ceiling reached only under attack, since a real fleet holds one
 * scope per exporter and a few hundred interfaces in total.
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
     * <p>Rejection here only degrades — static pins and live SNMP still resolve the interface — so
     * this is the tightest budget and the quietest one.
     */
    private int maxIfIndexesPerScope = 128;

    /**
     * How long an admitted source may go unheard before its budget is released.
     *
     * <p>Matches the default template timeout, so admission state does not outlive the state it
     * governs. It is what makes the bound recover on its own after a flood stops rather than
     * staying full until restart.
     */
    private Duration sourceIdleTimeout = Duration.ofMinutes(30);
}
