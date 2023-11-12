package org.riptide.classification;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class ClassificationEnricher extends Enricher.Single {

    @NonNull
    private final ClassificationEngine classificationEngine;

    @Override
    protected CompletableFuture<Void> enrich(Source source, EnrichedFlow flow) {
        final var request = ClassificationRequest.builder()
                .withExporterAddress(IpAddr.of(source.getExporterAddr()))
                .withLocation(source.getLocation())
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
