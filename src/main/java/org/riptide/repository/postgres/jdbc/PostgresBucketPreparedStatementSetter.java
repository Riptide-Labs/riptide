package org.riptide.repository.postgres.jdbc;

import org.riptide.bucketize.Bucket;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.utils.Tuple;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static org.riptide.repository.postgres.PostgresUtils.nullSafeTimestamp;

public class PostgresBucketPreparedStatementSetter implements BatchPreparedStatementSetter {

    private final List<Tuple<EnrichedFlow, Bucket>> buckets;

    public PostgresBucketPreparedStatementSetter(List<Tuple<EnrichedFlow, Bucket>> buckets) {
        this.buckets = Objects.requireNonNull(buckets);
    }

    @Override
    public void setValues(PreparedStatement ps, int i) throws SQLException {
        final var flow = buckets.get(i).first();
        final var bucket = buckets.get(i).second();
        ps.setTimestamp(1, nullSafeTimestamp(bucket.getBucketTime()));
        ps.setObject(2, bucket.getBytes());
        ps.setObject(3, bucket.getPackets());
        PostgresFlowPreparedStatementSetter.applyValues(ps, flow, 3);
    }

    @Override
    public int getBatchSize() {
        return buckets.size();
    }
}
