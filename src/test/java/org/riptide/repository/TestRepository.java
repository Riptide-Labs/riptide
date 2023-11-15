package org.riptide.repository;

import org.riptide.pipeline.EnrichedFlow;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class TestRepository implements FlowRepository {

    private final AtomicLong count = new AtomicLong(0);

    private final List<Collection<EnrichedFlow>> flows = Collections.synchronizedList(new LinkedList<>());

    @Override
    public void persist(final Collection<EnrichedFlow> flows) {
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

    public Map<String, FlowRepository> asRepositoriesMap() {
        return Map.of("test-repository", this);
    }
}
