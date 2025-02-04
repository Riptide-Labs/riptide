package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;

import java.io.IOException;
import java.util.List;

/**
 * Persistence interface for flows.
 *
 * After parsing and processing of received flows, the result is passed to all exposed instances of this interface.
 */
public interface FlowRepository {

    /**
     * Persist a batch of flows.
     *
     * @param flows the flows which should be persisted
     *
     * @throws FlowException on any error happening during processing.
     */
    void persist(List<EnrichedFlow> flows) throws FlowException, IOException;
}
