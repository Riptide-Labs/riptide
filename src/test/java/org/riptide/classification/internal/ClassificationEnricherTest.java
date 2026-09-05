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
import static org.mockito.Mockito.when;

@SpringBootTest
public class ClassificationEnricherTest {

    /**
     * An IP protocol number {@code Protocols} does not map, so {@code Protocols.getProtocol(Integer)}
     * answers null for it. This is the only way a null protocol arises from the wire:
     * {@code Flow.getProtocol()} is a primitive {@code int} and both the v9 and IPFIX builders default
     * an absent protocol to 0, which is HOPOPT and mapped. 143-252 are unmapped in the table; the row
     * below asserts this one still is.
     */
    private static final int UNMAPPED_PROTOCOL = 200;

    /**
     * A destination port named by a bundled rule that names no protocol. The eleven {@code boe-*} rules
     * cover 6400-6410 and are the ruleset's answer for traffic whose protocol is not one of the four
     * the other rules name.
     */
    private static final int PROTOCOL_LESS_RULE_PORT = 6405;

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
     *
     * <p>Both directions are asserted by value rather than by "did not throw", because both are ways to
     * get this wrong and only one of them is a crash. Port 80 pins that the rules naming a protocol do
     * not claim a flow that has none: every bundled rule on 80 names one, so a matcher that answered
     * {@code true} for an absent protocol would return a name here. Port 6405 pins the complement, that
     * the guard does not over-suppress and the protocol-less rules still answer.
     */
    @Test
    public void aRequestWithoutAProtocolIsAnsweredByTheRulesThatNameNoProtocol() {
        assertThat(Protocols.getProtocol(UNMAPPED_PROTOCOL))
                .as("%s must be a protocol number riptide does not map, or this row proves nothing",
                        UNMAPPED_PROTOCOL)
                .isNull();

        assertThat(this.classificationEngine.classify(request(UNMAPPED_PROTOCOL, 54321, 80)))
                .as("every bundled rule naming port 80 also names a protocol, so none may claim this")
                .isNull();

        assertThat(this.classificationEngine.classify(
                request(UNMAPPED_PROTOCOL, 54321, PROTOCOL_LESS_RULE_PORT)))
                .as("the protocol-less rules must still answer a flow whose protocol is unmapped")
                .isEqualTo("boe-pagesvr");

        // the control: the same port with a mapped protocol is unchanged by the guard
        assertThat(this.classificationEngine.classify(request(6, 54321, PROTOCOL_LESS_RULE_PORT)))
                .isEqualTo("boe-pagesvr");
    }

    private static ClassificationRequest request(final int protocol, final int srcPort, final int dstPort) {
        return ClassificationRequest.builder()
                .withProtocol(Protocols.getProtocol(protocol))
                .withSrcPort(srcPort)
                .withDstPort(dstPort)
                .build();
    }

    /**
     * The batch row. {@code Pipeline.process} enriches a whole batch inside one try/catch, so one flow
     * that throws costs the entire packet a {@code FlowException} and nothing is persisted.
     *
     * <p>The whole batch is stated in one assertion, so the row fails readably on a short list and so
     * the middle flow carries its own evidence: it is aimed at 6405, where a protocol-less rule answers,
     * which makes it positive proof that the guard did not simply suppress the flow.
     */
    @Test
    public void oneFlowWithAnUnmappedProtocolDoesNotFailItsBatch() throws Exception {
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = pipeline(repository);

        final var source = new Source("here", InetAddress.getLoopbackAddress());
        final var flows = List.of(
                flow(6, 80, 36592),                                          // TCP, http
                flow(UNMAPPED_PROTOCOL, 54321, PROTOCOL_LESS_RULE_PORT),     // used to poison the batch
                flow(17, 41234, 123));                                       // UDP, ntp

        pipeline.process(source, flows);

        assertThat(repository.flows().map(EnrichedFlow::getApplication))
                .containsExactly("http", "boe-pagesvr", "ntp");
    }

    /**
     * The address half of the same defect, one line above the matchers. A NetFlow v9 or IPFIX template
     * need not carry an address field, and both builders answer null when it is missing;
     * {@code ClassificationEnricher} hands that straight to {@code IpAddr.of(InetAddress)}, which used
     * to dereference it. That threw inside the same batch-wide try/catch and so cost the whole packet
     * in exactly the same way, and it threw <em>before</em> any {@code IpMatcher} guard could be
     * reached. The flow still classifies here, which is what separates "the address is absent" from
     * "the flow was dropped".
     */
    @Test
    public void oneFlowWithNoAddressDoesNotFailItsBatch() throws Exception {
        final var repository = new TestRepository(metricRegistry);
        final var pipeline = pipeline(repository);

        final var addressless = flow(6, 80, 36592);
        when(addressless.getSrcAddr()).thenReturn(null);
        when(addressless.getDstAddr()).thenReturn(null);

        final var source = new Source("here", InetAddress.getLoopbackAddress());

        pipeline.process(source, List.of(flow(17, 41234, 123), addressless));

        assertThat(repository.flows().map(EnrichedFlow::getApplication))
                .containsExactly("ntp", "http");
    }

    private Pipeline pipeline(final TestRepository repository) {
        return new Pipeline(
                List.<Enricher>of(new ClassificationEnricher(this.classificationEngine)),
                repository.asPersister(),
                this.metricRegistry,
                this.flowMapper);
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
