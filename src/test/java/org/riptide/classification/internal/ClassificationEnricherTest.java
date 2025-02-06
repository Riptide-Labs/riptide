package org.riptide.classification.internal;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationEnricher;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.Source;
import org.riptide.repository.TestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest()
public class ClassificationEnricherTest {

    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Autowired
    ClassificationEngine classificationEngine;

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void testEnrichment() throws Exception {
        final var enrichers = List.<Enricher>of(new ClassificationEnricher(this.classificationEngine));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersisters(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcPort()).thenReturn(80);
        when(flow.getDstPort()).thenReturn(36592);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getProtocol()).thenReturn(6); // TCP

        final var source = new Source("here", InetAddress.getByName("127.0.0.1"));

        pipeline.process(source, List.of(flow));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getApplication()).isEqualTo("http");
        });
    }
}
