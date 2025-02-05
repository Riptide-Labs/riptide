package org.riptide.repository.postgres;

import org.riptide.bucketize.Bucket;
import org.riptide.config.PostgresConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.postgres.jdbc.PostgresBucketPreparedStatementSetter;
import org.riptide.repository.postgres.jdbc.PostgresFlowPreparedStatementSetter;
import org.riptide.repository.postgres.jdbc.PostgresQueries;
import org.riptide.utils.Tuple;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class PostgresRepository implements FlowRepository {

    private final JdbcTemplate template;
    private final PostgresConfig config;

    public PostgresRepository(final PostgresConfig config,  DataSource dataSource) {
        Objects.requireNonNull(dataSource);
        this.template = new JdbcTemplate(dataSource);
        this.config = Objects.requireNonNull(config);
        this.init();
    }

    @Override
    public void persist(Collection<EnrichedFlow> flows) throws FlowException, IOException {
        final var list = new ArrayList<>(flows);
        if (config.isPersistFlows()) {
            persistFlows(list);
        }
        if (config.isPersistBuckets()) {
            persistBuckets(list);
        }
    }

    private void persistBuckets(List<EnrichedFlow> list) {
        final var buckets = list.stream().flatMap(flow -> {
            // TODO MVR make 5 seconds configurable
            return Bucket.bucketize(flow, Duration.ofSeconds(5)).values()
                    .stream()
                    .map(it -> Tuple.of(flow, it));
        }).toList();
        template.batchUpdate(PostgresQueries.QUERY_INSERT_BUCKETS, new PostgresBucketPreparedStatementSetter(buckets));
    }

    private void persistFlows(final List<EnrichedFlow> flows) {
        template.batchUpdate(PostgresQueries.QUERY_INSERT_FLOWS, new PostgresFlowPreparedStatementSetter(flows));
    }

    private void init() {
        template.execute(PostgresQueries.QUERY_DROP_TABLE_FLOWS);
        template.execute(PostgresQueries.QUERY_CREATE_TABLE_FLOWS);
        template.execute(PostgresQueries.QUERY_DROP_TABLE_BUCKETS);
        template.execute(PostgresQueries.QUERY_CREATE_TABLE_BUCKETS);
    }
}
