/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import lombok.NonNull;
import org.riptide.inventory.Inventory;
import lombok.RequiredArgsConstructor;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.EnricherOrder;
import org.riptide.pipeline.Source;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Stamps the matched node's name onto every flow as {@code exporterName}: the
 * human-readable identity dashboards prefer over the raw exporter address. Flows from
 * an exporter no node covers keep the field unset (persisted as the empty string).
 *
 * <p>Resolved once per batch: the {@link Source} is constant across a batch, so the
 * matched entry is too, and per-flow cost stays independent of inventory size (FR-3).
 * The name is the entry key, which for a prefix entry labels every device it covers.</p>
 */
@Component
@Order(EnricherOrder.EXPORTER_NAME)
@RequiredArgsConstructor
public class ExporterNameEnricher implements Enricher {

    @NonNull
    private final Inventory inventory;

    @Override
    public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
        // captured once for the batch: a per-flow snapshot() read would straddle a
        // reload inside one batch, which is the split view AD-3 exists to prevent
        this.inventory.snapshot().exporterView().match(source.identity())
                .ifPresent(entry -> flows.forEach(flow -> flow.setExporterName(entry.name())));
        return CompletableFuture.completedFuture(null);
    }
}
