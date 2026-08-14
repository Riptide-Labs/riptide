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

    InventorySnapshot(final PinnedPrefixMatcher<AgentEntry> agents,
                      final PinnedPrefixMatcher<ExporterEntry> exporters) {
        this.agents = agents;
        this.exporters = exporters;
    }

    /** The valid empty inventory: no ranges, no enrichment entries. */
    public static InventorySnapshot empty() {
        return new InventorySnapshot(
                PinnedPrefixMatcher.<AgentEntry>builder().build(),
                PinnedPrefixMatcher.<ExporterEntry>builder().build());
    }

    public AgentView agentView() {
        return identity -> this.agents.lookup(probe(identity), domain(identity));
    }

    public ExporterView exporterView() {
        return identity -> this.exporters.lookup(probe(identity), domain(identity));
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
