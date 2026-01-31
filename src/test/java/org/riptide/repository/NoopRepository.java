package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/// NOOP implementation of a FlowRepository, otherwise spring context dies, which we for test purposes
/// don't want, but in production is the correct behaviour.
@Service
@ConditionalOnBooleanProperty(havingValue = false, name = {
        "riptide.clickhouse.enabled",
        "riptide.elastic.enabled",
        "riptide.opensearch.enabled",
        "riptide.postgres.enabled",
})
public class NoopRepository implements FlowRepository {
    @Override
    public void persist(List<EnrichedFlow> flows) throws FlowException, IOException {

    }
}
