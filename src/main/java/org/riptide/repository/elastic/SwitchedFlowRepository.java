package org.riptide.repository.elastic;

import java.util.Collection;
import java.util.Objects;

import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eventually forwards flows to delegate flow repository.
 *
 * Whether the flows are forwarded can be controlled by a property.
 */
public class SwitchedFlowRepository implements FlowRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SwitchedFlowRepository.class);

    private final FlowRepository delegate;

    private boolean enabled = true;

    public SwitchedFlowRepository(final FlowRepository delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void persist(final Collection<EnrichedFlow> flows) throws FlowException {
        if (!this.enabled) {
            return;
        }

        this.delegate.persist(flows);
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDisabled() { return !this.enabled; }

    public void setDisabled(final boolean disabled) {
        this.enabled = !disabled;
    }
}
