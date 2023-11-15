package org.riptide.snmp;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class SnmpEnricher implements Enricher {

    @NonNull
    private final SnmpService snmpService;

    @NonNull
    private final SnmpConfiguration snmpConfiguration;

    @Override
    public CompletableFuture<Void> enrich(final Source source, final List<EnrichedFlow> flows) {
        return CompletableFuture.supplyAsync(() -> {
            this.snmpConfiguration.lookup(source.getExporterAddr()).ifPresent(snmpEndpoint -> {
                for (final EnrichedFlow flow : flows) {
                    this.snmpService.getIfName(snmpEndpoint, flow.getInputSnmp()).ifPresent(flow::setInputSnmpIfName);
                    this.snmpService.getIfName(snmpEndpoint, flow.getOutputSnmp()).ifPresent(flow::setOutputSnmpIfName);
                }
            });

            return null;
        });
    }
}
