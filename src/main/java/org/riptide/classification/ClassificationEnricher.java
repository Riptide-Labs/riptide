/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "riptide.enricher.classification.enabled", havingValue = "true", matchIfMissing = true)
public class ClassificationEnricher extends Enricher.Single {

    @NonNull
    private final ClassificationEngine classificationEngine;

    @Override
    protected CompletableFuture<Void> enrich(Source source, EnrichedFlow flow) {
        final var request = ClassificationRequest.builder()
                .withExporterAddress(IpAddr.of(source.getExporterAddr()))
                .withZone(source.getZone())
                .withProtocol(Protocols.getProtocol(flow.getProtocol()))
                .withSrcAddress(IpAddr.of(flow.getSrcAddr()))
                .withSrcPort(flow.getSrcPort())
                .withDstAddress(IpAddr.of(flow.getDstAddr()))
                .withDstPort(flow.getDstPort())
                .build();

        final var application = this.classificationEngine.classify(request);
        if (application != null) {
            flow.setApplication(application);
        }

        return CompletableFuture.completedFuture(null);
    }
}
