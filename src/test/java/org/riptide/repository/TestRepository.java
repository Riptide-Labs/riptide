package org.riptide.repository;

import com.codahale.metrics.MetricRegistry;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowPersister;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class TestRepository implements FlowRepository {

    private final AtomicLong count = new AtomicLong(0);

    private final List<Collection<EnrichedFlow>> flows = Collections.synchronizedList(new LinkedList<>());
    private final MetricRegistry metricRegistry;

    public TestRepository(MetricRegistry metricRegistry) {
        this.metricRegistry = Objects.requireNonNull(metricRegistry);
    }

    @Override
    public void persist(final List<EnrichedFlow> flows) {
        this.flows.add(flows);
        this.count.addAndGet(flows.size());
    }

    public Stream<EnrichedFlow> flows() {
        return this.flows.stream()
                .flatMap(Collection::stream);
    }

    public long count() {
        return this.count.get();
    }

    public FlowPersister asPersister() {
        return new FlowPersister("test-repository", this, metricRegistry);
    }
}
