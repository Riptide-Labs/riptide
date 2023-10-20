package org.riptide.flows.repository;

import org.riptide.flows.Flow;
import org.riptide.flows.pipeline.EnrichedFlow;
import org.riptide.flows.pipeline.FlowException;

import java.util.Collection;

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
    void persist(final Collection<EnrichedFlow> flows) throws FlowException;
}
