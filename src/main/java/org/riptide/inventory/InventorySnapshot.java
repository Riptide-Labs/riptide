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

    InventorySnapshot(final PinnedPrefixMatcher<AgentEntry> agents,
                      final PinnedPrefixMatcher<ExporterEntry> exporters) {
        this.agents = agents;
        this.exporters = exporters;
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
        // cannot parse switch record patterns (the NodeRegistry.lookup constraint);
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
