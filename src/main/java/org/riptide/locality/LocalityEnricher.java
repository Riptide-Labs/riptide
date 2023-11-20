package org.riptide.locality;

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
                    ? EnrichedFlow.Locality.PRIVATE
                    : EnrichedFlow.Locality.PUBLIC);
        }

        if (flow.getDstAddr() != null) {
            flow.setDstLocality(isPrivateAddress(flow.getDstAddr())
                    ? EnrichedFlow.Locality.PRIVATE
                    : EnrichedFlow.Locality.PUBLIC);
        }

        if (EnrichedFlow.Locality.PUBLIC.equals(flow.getDstLocality()) || EnrichedFlow.Locality.PUBLIC.equals(flow.getSrcLocality())) {
            flow.setFlowLocality(EnrichedFlow.Locality.PUBLIC);
        } else if (EnrichedFlow.Locality.PRIVATE.equals(flow.getDstLocality()) || EnrichedFlow.Locality.PRIVATE.equals(flow.getSrcLocality())) {
            flow.setFlowLocality(EnrichedFlow.Locality.PRIVATE);
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
