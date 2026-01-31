package org.riptide.bucketize;

import com.google.common.math.LongMath;
import lombok.Builder;
import lombok.Value;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.utils.Durations;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

@Value
@Builder
public class Bucket {
    Instant bucketTime;
    Duration duration;
    double packets;
    double bytes;
    double packetsPerSecond;
    double bytesPerSecond;

    public static Map<Instant, Bucket> bucketize(final EnrichedFlow flow, final Duration samplingInterval) {
        if (!samplingInterval.isPositive()) {
            throw new IllegalArgumentException("Sampling interval must be positive");
        }

        if (flow.getDeltaSwitched().isAfter(flow.getLastSwitched())) {
            throw new IllegalArgumentException("Flow has negative duration");
        }

        final var firstBucket = LongMath.divide(flow.getDeltaSwitched().toEpochMilli(), samplingInterval.toMillis(), RoundingMode.FLOOR);
        final var lastBucket = LongMath.divide(flow.getLastSwitched().toEpochMilli(), samplingInterval.toMillis(), RoundingMode.CEILING);

        final var flowDuration = Duration.between(flow.getDeltaSwitched(), flow.getLastSwitched());
        if (flowDuration.isZero()) {
            final var bucket = Instant.ofEpochMilli((firstBucket + 1) * samplingInterval.toMillis());
            return Map.of(bucket, Bucket.builder()
                    .duration(samplingInterval)
                    .bucketTime(bucket)
                    .packets(flow.getPackets())
                    .bytes(flow.getBytes())
                    .bytesPerSecond(flow.getBytes())
                    .packetsPerSecond(flow.getPackets())
                    .build());
        }

        final var buckets = lastBucket - firstBucket;
        if (buckets == 1) {
            // Flow fits into single bucket
            final var bucket = Instant.ofEpochMilli((firstBucket + 1) * samplingInterval.toMillis());
            return Map.of(bucket, Bucket.builder()
                    .duration(samplingInterval)
                    .bucketTime(bucket)
                    .packets(flow.getPackets())
                    .bytes(flow.getBytes())
                    .bytesPerSecond(flow.getBytes() / Durations.seconds(samplingInterval))
                    .packetsPerSecond(flow.getPackets() / Durations.seconds(samplingInterval))
                    .build());
        }

        final var result = new TreeMap<Instant, Bucket>();
        for (int i = 0; i < buckets; i++) {
            final var bucketEnd = Instant.ofEpochMilli((firstBucket + i + 1) * samplingInterval.toMillis());
            final var bucketStart = bucketEnd.minus(samplingInterval);

            final double proportion;
            if (flow.getDeltaSwitched().isAfter(bucketStart)) {
                // Flow starts in bucket, but ends later
                final var part = Duration.between(flow.getDeltaSwitched(), bucketEnd);
                proportion = Durations.seconds(part) / Durations.seconds(flowDuration);
            } else if (flow.getLastSwitched().isBefore(bucketEnd)) {
                // Flow ends in bucket, but starts earlier
                final var part = Duration.between(bucketStart, flow.getLastSwitched());
                proportion = Durations.seconds(part) / Durations.seconds(flowDuration);
            } else {
                // Flow expands over the whole bucket
                proportion = Durations.seconds(samplingInterval) / Durations.seconds(flowDuration);
            }

            result.put(bucketEnd, Bucket.builder().duration(samplingInterval)
                    .packets(flow.getPackets() * proportion)
                    .bytes(flow.getBytes() * proportion)
                    .bytesPerSecond(flow.getBytes() * proportion / Durations.seconds(samplingInterval))
                    .packetsPerSecond(flow.getPackets() * proportion / Durations.seconds(samplingInterval))
                    .bucketTime(bucketEnd)
                    .build());
        }
        return result;
    }
}
