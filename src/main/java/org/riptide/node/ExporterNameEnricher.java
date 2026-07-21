/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.EnricherOrder;
import org.riptide.pipeline.Source;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Stamps the matched node's name onto every flow as {@code exporterName} — the
 * human-readable identity dashboards prefer over the raw exporter address. Flows from
 * an exporter no node covers keep the field unset (persisted as the empty string).
 */
@Component
@Order(EnricherOrder.EXPORTER_NAME)
@RequiredArgsConstructor
public class ExporterNameEnricher extends Enricher.Single {

    @NonNull
    private final NodeRegistry nodeRegistry;

    @Override
    protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
        this.nodeRegistry.lookup(source.identity())
                .ifPresent(node -> flow.setExporterName(node.label()));
        return CompletableFuture.completedFuture(null);
    }
}
