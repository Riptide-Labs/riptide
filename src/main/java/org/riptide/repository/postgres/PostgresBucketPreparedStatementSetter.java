package org.riptide.repository.postgres;

import org.riptide.bucketize.Bucket;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.utils.Tuple;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

public class PostgresBucketPreparedStatementSetter implements BatchPreparedStatementSetter {

    private final List<Tuple<EnrichedFlow, Bucket>> buckets;
    private final PostgresFlowPreparedStatementSetter flowPreparedSetter;

    public PostgresBucketPreparedStatementSetter(List<Tuple<EnrichedFlow, Bucket>> buckets) {
        this.buckets = Objects.requireNonNull(buckets);
        this.flowPreparedSetter = new PostgresFlowPreparedStatementSetter(3, () -> buckets.stream().map(Tuple::first).toList());
    }

    @Override
    public void setValues(PreparedStatement ps, int i) throws SQLException {
        final var flow = buckets.get(i).first();
        final var bucket = buckets.get(i).second();
        ps.setTimestamp(1, new Timestamp(bucket.getBucketTime().toEpochMilli()));
        ps.setObject(2, bucket.getBytes());
        ps.setObject(3, bucket.getPackets());
        flowPreparedSetter.setValues(ps, flow);
    }

    @Override
    public int getBatchSize() {
        return buckets.size(); // TODO MVR probably try to cache this
    }
}
