package org.riptide.repository.yugabyte;

import lombok.RequiredArgsConstructor;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

@RequiredArgsConstructor
public class JdbcFlowRepository implements FlowRepository {

    private final FlowJdbcRepository repository;

    private final FlowEntitiy.FlowEntityMapper documentMapper;

    @Override
    public void persist(final Collection<EnrichedFlow> flows) throws FlowException, IOException {
        for (final var flow: flows) {
            final var doc = this.documentMapper.from(flow);
            doc.setId(UUID.randomUUID()); // TODO fooker: use faster random

            this.repository.save(doc);
        }
    }
}
