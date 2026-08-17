/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import inet.ipaddr.IPAddressString;
import org.riptide.inventory.PinnedPrefixMatcher;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.List;

/**
 * The semantics seam bound directly to {@link PinnedPrefixMatcher}, proving the
 * standalone component honours the characterisation contract without the
 * {@code NodeRegistry} facade that used to sit in front of it (removed in 0.9).
 */
final class PinnedPrefixMatcherMatchSemantics implements ExporterMatchSemantics {

    @Override
    public Matcher build(final List<Entry> entries) {
        final PinnedPrefixMatcher.Builder<String> builder = PinnedPrefixMatcher.builder();
        for (final Entry entry : entries) {
            if (entry.subnet() == null) {
                throw new IllegalStateException(
                        "Entry '%s' has no subnet — every entry needs one to match exporters.".formatted(entry.name()));
            }
            builder.add(entry.name(), new IPAddressString(entry.subnet()), entry.observationDomainPin(), entry.name());
        }
        final PinnedPrefixMatcher<String> matcher = builder.build();
        return identity -> matcher.lookup(probe(identity), domain(identity));
    }

    private static IPAddressString probe(final ExporterIdentity identity) {
        return new IPAddressString(deviceAddress(identity).getHostAddress());
    }

    private static InetAddress deviceAddress(final ExporterIdentity identity) {
        if (identity instanceof ExporterIdentity.NetflowIpfix netflowIpfix) {
            return netflowIpfix.source();
        }
        if (identity instanceof ExporterIdentity.Sflow sflow) {
            return sflow.agentAddress();
        }
        throw new IllegalStateException("Unhandled exporter identity: " + identity);
    }

    private static long domain(final ExporterIdentity identity) {
        if (identity instanceof ExporterIdentity.NetflowIpfix netflowIpfix) {
            return netflowIpfix.observationDomain();
        }
        if (identity instanceof ExporterIdentity.Sflow sflow) {
            return sflow.subAgentId();
        }
        throw new IllegalStateException("Unhandled exporter identity: " + identity);
    }
}
