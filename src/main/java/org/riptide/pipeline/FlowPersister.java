package org.riptide.pipeline;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import org.riptide.repository.FlowRepository;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

public class FlowPersister {
    @Getter
    private final String name;
    private final FlowRepository repository;
    private final Timer logTimer;

    public FlowPersister(final String name, final FlowRepository repository, final MetricRegistry metricRegistry) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(name), "Name cannot be null or empty");
        Objects.requireNonNull(repository);
        Objects.requireNonNull(metricRegistry);
        this.repository = repository;
        this.logTimer = metricRegistry.timer(MetricRegistry.name("logPersisting", name));
        this.name = name;
    }

    public void persist(final Collection<EnrichedFlow> flows) throws FlowException, IOException {
        try (var ctx = this.logTimer.time()) {
            this.repository.persist(flows);
        }
    }
}
