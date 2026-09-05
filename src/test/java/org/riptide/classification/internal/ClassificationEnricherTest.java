/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationEnricher;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.Protocols;
import org.riptide.flows.parser.data.Flow;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@SpringBootTest
public class ClassificationEnricherTest {

    /**
     * An IP protocol number {@code Protocols} does not map, so {@code Protocols.getProtocol(Integer)}
     * answers null for it exactly as it does for a flow that carries no protocol at all. 143-252 are
     * unassigned in the table; the row below asserts this one still is.
     */
    private static final int UNMAPPED_PROTOCOL = 200;

    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Autowired
    ClassificationEngine classificationEngine;

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @Test
    public void testEnrichment() throws Exception {
        final var enrichers = List.<Enricher>of(new ClassificationEnricher(this.classificationEngine));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcPort()).thenReturn(80);
        when(flow.getDstPort()).thenReturn(36592);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        when(flow.getProtocol()).thenReturn(6); // TCP

        final var source = new Source("here", InetAddress.getLoopbackAddress());

        pipeline.process(source, List.of(flow));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getApplication()).isEqualTo("http");
        });
    }

    /**
     * The live shape of #750, against the bundled ruleset and through the real engine. A protocol
     * number riptide does not map becomes a null protocol on the request, which used to be
     * dereferenced by {@code ProtocolMatcher} and throw.
     */
    @Test
    public void aRequestWithoutAProtocolIsClassifiedInsteadOfThrowing() {
        assertThat(Protocols.getProtocol(UNMAPPED_PROTOCOL))
                .as("%s must be a protocol number riptide does not map, or this row proves nothing",
                        UNMAPPED_PROTOCOL)
                .isNull();

        final var request = ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol(UNMAPPED_PROTOCOL))
                .withSrcPort(54321)
                .withDstPort(80)
                .build();

        assertThatCode(() -> this.classificationEngine.classify(request)).doesNotThrowAnyException();
    }

    /**
     * The batch row. {@code Pipeline.process} enriches a whole batch inside one try/catch, so one flow
     * that throws costs the entire packet a {@code FlowException} and nothing is persisted. This asserts
     * the surviving flows by name, not just the count: a batch that reached the repository unclassified
     * would satisfy a count-only row. What the middle flow classifies as is deliberately not pinned
     * here — that it is not claimed by a protocol-naming rule is
     * {@code DefaultClassificationEngineTest.aRuleNamingAProtocolDoesNotMatchARequestWithoutOne}'s row,
     * on a ruleset this test does not have to keep in step with.
     */
    @Test
    public void oneFlowWithAnUnmappedProtocolDoesNotFailItsBatch() throws Exception {
        final var enrichers = List.<Enricher>of(new ClassificationEnricher(this.classificationEngine));
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = new Pipeline(enrichers, repository.asPersister(), this.metricRegistry, this.flowMapper);

        final var source = new Source("here", InetAddress.getLoopbackAddress());
        final var flows = List.of(
                flow(6, 80, 36592),                     // TCP, http
                flow(UNMAPPED_PROTOCOL, 12345, 54321),  // the flow that used to poison the batch
                flow(17, 41234, 123));                  // UDP, ntp

        pipeline.process(source, flows);

        assertThat(repository.count()).isEqualTo(3);
        final var applications = repository.flows().map(EnrichedFlow::getApplication).toList();
        assertThat(applications.get(0)).as("the good flow ahead of the bad one").isEqualTo("http");
        assertThat(applications.get(2)).as("the good flow behind the bad one").isEqualTo("ntp");
    }

    private static Flow flow(final int protocol, final int srcPort, final int dstPort) throws Exception {
        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getProtocol()).thenReturn(protocol);
        when(flow.getSrcPort()).thenReturn(srcPort);
        when(flow.getDstPort()).thenReturn(dstPort);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("10.10.10.10"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("10.20.20.10"));
        return flow;
    }
}
