package org.riptide.repository.postgres.jdbc;

import org.riptide.bucketize.Bucket;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.utils.Tuple;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        ps.setBigDecimal(2, BigDecimal.valueOf(bucket.getBytes()).setScale(4, RoundingMode.HALF_EVEN));
        ps.setBigDecimal(3, BigDecimal.valueOf(bucket.getPackets()).setScale(4, RoundingMode.HALF_EVEN));
        ps.setObject(4, bucket.getDuration().toSeconds());
        ps.setBigDecimal(5, BigDecimal.valueOf(bucket.getBytesPerSecond()).setScale(4, RoundingMode.HALF_UP));
        ps.setBigDecimal(6, BigDecimal.valueOf(bucket.getPacketsPerSecond()).setScale(4, RoundingMode.HALF_UP));
        PostgresFlowPreparedStatementSetter.applyValues(ps, flow, 6);
    }

    @Override
    public int getBatchSize() {
        return buckets.size();
    }
}
