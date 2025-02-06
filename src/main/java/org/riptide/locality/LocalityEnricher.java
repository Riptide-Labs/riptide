package org.riptide.locality;

import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;

@Component
public class LocalityEnricher extends Enricher.Single {

    @Override
    protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
        if (flow.getSrcAddr() != null) {
            flow.setSrcLocality(isPrivateAddress(flow.getSrcAddr())
                    ? Flow.Locality.PRIVATE
                    : Flow.Locality.PUBLIC);
        }

        if (flow.getDstAddr() != null) {
            flow.setDstLocality(isPrivateAddress(flow.getDstAddr())
                    ? Flow.Locality.PRIVATE
                    : Flow.Locality.PUBLIC);
        }

        if (Flow.Locality.PUBLIC.equals(flow.getDstLocality()) || Flow.Locality.PUBLIC.equals(flow.getSrcLocality())) {
            flow.setFlowLocality(Flow.Locality.PUBLIC);
        } else if (Flow.Locality.PRIVATE.equals(flow.getDstLocality()) || Flow.Locality.PRIVATE.equals(flow.getSrcLocality())) {
            flow.setFlowLocality(Flow.Locality.PRIVATE);
        }

        return CompletableFuture.completedFuture(null);
    }

    private static boolean isPrivateAddress(final InetAddress inetAddress) {
        return inetAddress.isLoopbackAddress()
                || inetAddress.isLinkLocalAddress()
                || inetAddress.isSiteLocalAddress()
                || inetAddress.isAnyLocalAddress()
                || inetAddress.isMCSiteLocal()
                || inetAddress.isMCLinkLocal()
                || inetAddress.isMCNodeLocal()
                || inetAddress.isMCOrgLocal();
    }
}
