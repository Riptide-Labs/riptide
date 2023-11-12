package org.riptide.dns;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;
import org.riptide.dns.netty.NettyDnsResolver;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Pipeline;
import org.riptide.pipeline.Source;
import org.riptide.repository.TestRepository;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

public class HostnameEnricherTest {

    private final MetricRegistry metricRegistry = new MetricRegistry();

    private final EnrichedFlow.FlowMapper flowMapper = Mappers.getMapper(EnrichedFlow.FlowMapper.class);

    @RegisterExtension
    static MockDnsServer dnsServer = new MockDnsServer(request -> {
        if (request.getType() == Type.PTR && request.getName().toString().equals("1.2.0.192.in-addr.arpa.")) {
            return List.of(Record.fromString(request.getName(), request.getType(), request.getDClass(), request.getTTL(), "one.example.com.", null));
        }
        if (request.getType() == Type.PTR && request.getName().toString().equals("2.2.0.192.in-addr.arpa.")) {
            return List.of(Record.fromString(request.getName(), request.getType(), request.getDClass(), request.getTTL(), "two.example.com.", null));
        }
        if (request.getType() == Type.PTR && request.getName().toString().equals("3.2.0.192.in-addr.arpa.")) {
            return List.of(Record.fromString(request.getName(), request.getType(), request.getDClass(), request.getTTL(), "tre.example.com.", null));
        }
        return List.of();
    });

    @Test
    public void testEnrichment() throws Exception {
        final var dnsResolver = new NettyDnsResolver(this.metricRegistry);
        dnsResolver.setNameservers("127.0.0.1:" + dnsServer.getPort());
        dnsResolver.init();

        final var enrichers = List.<Enricher>of(new HostnameEnricher(dnsResolver));

        final var repository = new TestRepository();
        final var pipeline = new Pipeline(enrichers, repository.asRepositoriesMap(), this.metricRegistry, this.flowMapper);

        final Flow flow = Mockito.mock(Flow.class);
        when(flow.getSrcAddr()).thenReturn(InetAddress.getByName("192.0.2.1"));
        when(flow.getDstAddr()).thenReturn(InetAddress.getByName("192.0.2.2"));
        when(flow.getNextHop()).thenReturn(InetAddress.getByName("192.0.2.3"));

        final var source = new Source("here", InetAddress.getByName("127.0.0.1"));

        pipeline.process(source, List.of(flow));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.flows()).allSatisfy(enrichedFlow -> {
            assertThat(enrichedFlow.getSrcAddrHostname()).isEqualTo("one.example.com");
            assertThat(enrichedFlow.getDstAddrHostname()).isEqualTo("two.example.com");
            assertThat(enrichedFlow.getNextHopHostname()).isEqualTo("tre.example.com");
        });
    }
}
