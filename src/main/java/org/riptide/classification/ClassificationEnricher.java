/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.riptide.pipeline.ApplicationSource;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Names the application, in ladder order: the exporter's own application table for the record's
 * applicationId, then the port and address rules. {@code applicationSource} records which rung
 * answered, so a dashboard can tell a Layer 7 name from a port guess.
 */
@Component
@Order(org.riptide.pipeline.EnricherOrder.CLASSIFICATION)
@ConditionalOnProperty(name = "riptide.enricher.classification.enabled", havingValue = "true", matchIfMissing = true)
public class ClassificationEnricher extends Enricher.Single {

    /**
     * The name Cisco NBAR2 gives an {@code applicationId} it has not classified yet, for example
     * the c8000v's PANA-L7 row {@code 0x0d000001}. The exporter did answer, but the answer is "no
     * answer": treating it as a name would let a router's not-yet-classified state permanently
     * outrank a port rule that already knows what the flow is, for example a fresh SSH connection an
     * NBAR2 engine has not finished fingerprinting.
     */
    static final String UNCLASSIFIED_NAME = "unknown";

    private final ClassificationEngine classificationEngine;

    private final ExporterApplicationTable applicationTable;

    /**
     * A non-zero applicationId the exporter's table could not name. Routine for the first refresh
     * interval after a restart, and permanent on an exporter that sends ids without a table.
     */
    private final Meter unresolved;

    public ClassificationEnricher(final ClassificationEngine classificationEngine,
                                  final ExporterApplicationTable applicationTable,
                                  final MetricRegistry metrics) {
        this.classificationEngine = Objects.requireNonNull(classificationEngine);
        this.applicationTable = Objects.requireNonNull(applicationTable);
        this.unresolved = metrics.meter(MetricRegistry.name("enrichment", "application", "unresolved"));
    }

    @Override
    protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
        final long applicationId = flow.getApplicationId() != null ? flow.getApplicationId() : 0L;
        if (applicationId != 0L) {
            final Optional<ApplicationInfo> named = this.applicationTable.lookup(source.identity(), applicationId);
            final String name = named.map(ApplicationInfo::name).orElse(null);
            if (name != null && !UNCLASSIFIED_NAME.equalsIgnoreCase(name.trim())) {
                flow.setApplication(name);
                flow.setApplicationSource(ApplicationSource.Exporter);
                flow.setApplicationDescription(named.map(ApplicationInfo::description).orElse(null));
                return CompletableFuture.completedFuture(null);
            }
            if (name == null) {
                this.unresolved.mark();
            }
        }

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
            flow.setApplicationSource(ApplicationSource.Rules);
        } else {
            flow.setApplicationSource(ApplicationSource.None);
        }

        return CompletableFuture.completedFuture(null);
    }
}
