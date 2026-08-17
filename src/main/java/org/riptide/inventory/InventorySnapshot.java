/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddressString;
import org.riptide.pipeline.ExporterIdentity;

/**
 * Both configuration trees, compiled off the hot path into one immutable object.
 * The agents trie and the exporters trie always come from the same build, so a
 * reader can never observe them from different config generations (AD-3): the
 * publisher swaps whole snapshot instances behind a single volatile reference,
 * and the views handed out here are plain accessors of this instance that never
 * re-read the published reference.
 */
public final class InventorySnapshot {

    private final PinnedPrefixMatcher<AgentEntry> agents;
    private final PinnedPrefixMatcher<ExporterEntry> exporters;
    private final AgentView agentView;
    private final ExporterView exporterView;
    /** The tree's key was written as a mapping in the source; see {@link #isRegressiveOver}. */
    private final boolean agentsDeclared;
    private final boolean exportersDeclared;

    InventorySnapshot(final PinnedPrefixMatcher<AgentEntry> agents,
                      final PinnedPrefixMatcher<ExporterEntry> exporters) {
        this(agents, exporters, false, false);
    }

    InventorySnapshot(final PinnedPrefixMatcher<AgentEntry> agents,
                      final PinnedPrefixMatcher<ExporterEntry> exporters,
                      final boolean agentsDeclared,
                      final boolean exportersDeclared) {
        this.agents = agents;
        this.exporters = exporters;
        this.agentsDeclared = agentsDeclared;
        this.exportersDeclared = exportersDeclared;
        // built once here rather than per call: a consumer that captures a view per
        // batch should pay a volatile read and nothing else
        this.agentView = identity -> this.agents.lookup(probe(identity), domain(identity));
        this.exporterView = identity -> this.exporters.lookup(probe(identity), domain(identity));
    }

    /** How many agent ranges this build carries. */
    public int agentCount() {
        return this.agents.size();
    }

    /** How many enrichment entries this build carries. */
    public int exporterCount() {
        return this.exporters.size();
    }

    /** The valid empty inventory: no ranges, no enrichment entries. */
    public static InventorySnapshot empty() {
        return new InventorySnapshot(
                PinnedPrefixMatcher.<AgentEntry>builder().build(),
                PinnedPrefixMatcher.<ExporterEntry>builder().build());
    }

    /** True when the build produced no agent ranges and no enrichment entries. */
    public boolean isEmpty() {
        return this.agents.size() == 0 && this.exporters.size() == 0;
    }

    /**
     * True when publishing this snapshot over {@code previous} would drop an entire tree
     * without saying so: a tree going populated to empty is refused unless the source
     * wrote it as an explicit empty mapping ({@code agents: {}}). That distinction is what
     * separates a deliberate decommission from a torn read: a non-atomic writer flushes
     * the trees in file order, so a mid-write read is missing a tree entirely — it never
     * contains one it replaced with a literal {@code {}}. Both loss directions are live:
     * the converter emits {@code agents} first (a torn read drops every exporter name),
     * and an in-place editor can flush either tree last (a torn read deregisters the
     * whole polled fleet). The whole-file rule this replaces refused only total
     * annihilation and missed both.
     *
     * <p>Empty over empty stays legal, so boot and an intentionally empty fleet are
     * unchanged. A tree shrinking without vanishing also stays legal: bounded churn that
     * self-heals when the full write lands, and refusing it would mean shrink-ratio
     * heuristics rather than a binary, explainable rule. A bare {@code agents:} key with
     * no value does not count as declared — the marker requires an actual mapping, so a
     * write torn exactly on the key line is still refused.</p>
     */
    public boolean isRegressiveOver(final InventorySnapshot previous) {
        return (previous.agentCount() > 0 && agentCount() == 0 && !this.agentsDeclared)
                || (previous.exporterCount() > 0 && exporterCount() == 0 && !this.exportersDeclared);
    }

    /**
     * Capture once per unit of work and hold it (AD-3). Calling this per flow costs a
     * volatile read each time and, worse, can straddle a swap inside one batch, which
     * is exactly what one immutable snapshot exists to prevent. Never hold a view in a
     * field or expose one as a bean: it would pin the generation it was built from and
     * hot reload would go quietly nowhere.
     */
    public AgentView agentView() {
        return this.agentView;
    }

    /** Capture once per unit of work; see {@link #agentView()}. */
    public ExporterView exporterView() {
        return this.exporterView;
    }

    private static IPAddressString probe(final ExporterIdentity identity) {
        return new IPAddressString(identity.deviceAddress().getHostAddress());
    }

    private static long domain(final ExporterIdentity identity) {
        // instanceof instead of an exhaustive switch pattern only because checkstyle
        // cannot parse switch record patterns (the same constraint the retired node
        // registry's lookup carried);
        // new ExporterIdentity variants must be handled here
        if (identity instanceof ExporterIdentity.NetflowIpfix netflowIpfix) {
            return netflowIpfix.observationDomain();
        }
        if (identity instanceof ExporterIdentity.Sflow sflow) {
            return sflow.subAgentId();
        }
        throw new IllegalStateException("Unhandled exporter identity: " + identity);
    }
}
