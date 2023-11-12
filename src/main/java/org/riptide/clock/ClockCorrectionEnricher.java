package org.riptide.clock;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.riptide.config.enricher.ClockCorrectionConfiguration;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Enricher;
import org.riptide.pipeline.Source;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
@Getter
@Setter
@RequiredArgsConstructor
@ConditionalOnProperty(name = "riptide.enricher.clock-correction.enable", havingValue = "true", matchIfMissing = true)
public class ClockCorrectionEnricher extends Enricher.Single {

    @NonNull
    private final ClockCorrectionConfiguration configuration;

    @Override
    protected CompletableFuture<Void> enrich(final Source source, final EnrichedFlow flow) {
        if (flow.getFirstSwitched().isAfter(flow.getLastSwitched())) {

            // Re-calculate a (somewhat) valid timout from the flow timestamps
            final var timeout = (flow.getDeltaSwitched() != null && flow.getDeltaSwitched() != flow.getFirstSwitched())
                    ? Duration.between(flow.getDeltaSwitched(), flow.getLastSwitched())
                    : Duration.ZERO;

            flow.setLastSwitched(flow.getTimestamp());
            flow.setFirstSwitched(flow.getTimestamp().minus(timeout));
            flow.setDeltaSwitched(flow.getTimestamp().minus(timeout));
        }

        if (this.configuration.skewThresholdMs != 0) {
            final var skew = Duration.between(flow.getReceivedAt(), flow.getTimestamp());
            if (skew.abs().toMillis() >= this.configuration.skewThresholdMs) {
                // The applied correction is the negative skew
                flow.setClockCorrection(skew.negated());

                // Fix the skew on all timestamps of the flow
                flow.setTimestamp(flow.getTimestamp().minus(skew));
                flow.setFirstSwitched(flow.getFirstSwitched().minus(skew));
                flow.setDeltaSwitched(flow.getDeltaSwitched().minus(skew));
                flow.setLastSwitched(flow.getLastSwitched().minus(skew));
            }
        }

        return CompletableFuture.completedFuture(null);
    }
}
