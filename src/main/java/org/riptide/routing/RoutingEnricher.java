/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.routing;

import inet.ipaddr.IPAddressString;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Fills and names AS data from the static routing mapping. A <b>nonzero</b>
 * exporter-provided AS number always wins — the prefix mapping only fills zeros/absent
 * values. Names apply to the resulting number regardless of its origin. Without any
 * configured mapping this enricher is a no-op.
 */
@Component
@RequiredArgsConstructor
public class RoutingEnricher extends Enricher.Single {

    @NonNull
    private final RoutingConfig routingConfig;

    @Override
    protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
        if (!this.routingConfig.isEmpty()) {
            enrichSide(flow.getSrcAddr(), flow::getSrcAs, flow::setSrcAs, flow::getSrcAsOrg, flow::setSrcAsOrg);
            enrichSide(flow.getDstAddr(), flow::getDstAs, flow::setDstAs, flow::getDstAsOrg, flow::setDstAsOrg);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void enrichSide(final InetAddress address, final Supplier<Long> getAs,
                            final Consumer<Long> setAs, final Supplier<String> getOrg, final Consumer<String> setOrg) {
        // 1. fill AS number and org from the prefix table — only when the exporter sent 0/absent
        if (address != null && (getAs.get() == null || getAs.get() == 0)) {
            this.routingConfig.lookupPrefix(new IPAddressString(address.getHostAddress())).ifPresent(info -> {
                if (info.asn() != null) {
                    setAs.accept(info.asn());
                }
                if (info.org() != null) {
                    setOrg.accept(info.org());
                }
            });
        }
        // 2. name whatever number the flow ends up with (exporter-provided or just
        // filled) — a prefix-provided org is not overwritten
        final Long effective = getAs.get();
        if (effective != null && effective != 0 && getOrg.get() == null) {
            this.routingConfig.lookupAsName(effective).ifPresent(setOrg);
        }
    }
}
