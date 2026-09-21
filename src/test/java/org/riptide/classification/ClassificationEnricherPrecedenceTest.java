/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.session.SessionAdmissionConfig;
import org.riptide.pipeline.ApplicationSource;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.pipeline.Source;
import org.riptide.snmp.SnmpOptionsConfig;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ladder for {@code application}: an exporter-named id wins, the port rules fill the rest,
 * and {@code applicationSource} says which one answered.
 */
class ClassificationEnricherPrecedenceTest {

    private static final long HTTP = 0x03000050L;
    private static final long ICMP = 0x01000001L;

    private final MetricRegistry metrics = new MetricRegistry();
    private final ClassificationEngine engine = mock(ClassificationEngine.class);
    private final ExporterApplicationTable table =
            new ExporterApplicationTable(new SnmpOptionsConfig(), new SessionAdmissionConfig(), this.metrics);
    private final ClassificationEnricher enricher = new ClassificationEnricher(this.engine, this.table, this.metrics);

    private static Source source() throws Exception {
        return new Source("here", new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.10.3.1"), 256));
    }

    private static EnrichedFlow flow(final long applicationId) throws Exception {
        return EnrichedFlow.builder()
                .srcAddr(InetAddress.getByName("10.10.1.10")).srcPort(56006)
                .dstAddr(InetAddress.getByName("10.10.2.10")).dstPort(80)
                .protocol(6)
                .applicationId(applicationId)
                .build();
    }

    private void tableNames(final long id, final String name) throws Exception {
        this.table.accept(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.10.3.1"), 6),
                List.of(new UnsignedValue("applicationId", id)),
                List.of(new StringValue("applicationName", name)));
    }

    @Test
    void anExporterNamedIdWinsOverTheRules() throws Exception {
        tableNames(HTTP, "http");
        when(this.engine.classify(any())).thenReturn("www");
        final EnrichedFlow flow = flow(HTTP);

        this.enricher.enrich(source(), List.of(flow)).join();

        assertThat(flow.getApplication()).isEqualTo("http");
        assertThat(flow.getApplicationSource()).isEqualTo(ApplicationSource.Exporter);
        verify(this.engine, never()).classify(any());
    }

    @Test
    void anUnresolvedIdFallsToTheRulesAndIsCounted() throws Exception {
        when(this.engine.classify(any())).thenReturn("www");
        final EnrichedFlow flow = flow(ICMP);

        this.enricher.enrich(source(), List.of(flow)).join();

        assertThat(flow.getApplication()).isEqualTo("www");
        assertThat(flow.getApplicationSource()).isEqualTo(ApplicationSource.Rules);
        assertThat(this.metrics.meter("enrichment.application.unresolved").getCount()).isEqualTo(1);
    }

    /**
     * A row that carries a description and no name is a stored row that still names nothing, so it
     * is not a hit: the ladder falls to the rules and the id counts as unresolved, exactly as it
     * would if the table held no row at all.
     */
    @Test
    void aDescriptionOnlyRowFallsToTheRulesAndIsCounted() throws Exception {
        this.table.accept(new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.10.3.1"), 6),
                List.of(new UnsignedValue("applicationId", HTTP)),
                List.of(new StringValue("applicationDescription", "World Wide Web traffic")));
        when(this.engine.classify(any())).thenReturn("www");
        final EnrichedFlow flow = flow(HTTP);

        this.enricher.enrich(source(), List.of(flow)).join();

        assertThat(flow.getApplication()).isEqualTo("www");
        assertThat(flow.getApplicationSource()).isEqualTo(ApplicationSource.Rules);
        assertThat(this.metrics.meter("enrichment.application.unresolved").getCount()).isEqualTo(1);
    }

    @Test
    void aZeroIdNeverConsultsTheTableAndIsNotUnresolved() throws Exception {
        when(this.engine.classify(any())).thenReturn("www");
        final EnrichedFlow flow = flow(0L);

        this.enricher.enrich(source(), List.of(flow)).join();

        assertThat(flow.getApplication()).isEqualTo("www");
        assertThat(flow.getApplicationSource()).isEqualTo(ApplicationSource.Rules);
        assertThat(this.metrics.meter("enrichment.application.unresolved").getCount()).isZero();
    }

    @Test
    void noRuleAndNoNameLeavesTheApplicationNullWithSourceNone() throws Exception {
        when(this.engine.classify(any())).thenReturn(null);
        final EnrichedFlow flow = flow(0L);

        this.enricher.enrich(source(), List.of(flow)).join();

        assertThat(flow.getApplication()).isNull();
        assertThat(flow.getApplicationSource()).isEqualTo(ApplicationSource.None);
    }

    @Test
    void aNullApplicationIdOnTheEnrichedFlowReadsAsZero() throws Exception {
        when(this.engine.classify(any())).thenReturn("www");
        final EnrichedFlow flow = flow(0L);
        flow.setApplicationId(null);

        this.enricher.enrich(source(), List.of(flow)).join();

        assertThat(flow.getApplicationSource()).isEqualTo(ApplicationSource.Rules);
    }
}
