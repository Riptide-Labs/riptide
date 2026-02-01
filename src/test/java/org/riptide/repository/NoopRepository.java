package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/// NOOP implementation of a FlowRepository, otherwise spring context dies, which we for test purposes
/// don't want, but is in production the correct behavior.
@Service
public class NoopRepository implements FlowRepository {
    @Override
    public void persist(List<EnrichedFlow> flows) throws FlowException, IOException {

    }
}
