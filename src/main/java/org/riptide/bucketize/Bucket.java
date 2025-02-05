package org.riptide.bucketize;

import com.google.common.math.LongMath;
import lombok.Builder;
import lombok.Value;
import org.riptide.pipeline.EnrichedFlow;

import java.math.RoundingMode;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

@Value
@Builder
public class Bucket {
    Long srcAs;
    Long dstAs;

    InetAddress srcAddress;
    InetAddress dstAddress;

    String srcHostname;
    String dstHostname;

    String application;
    String exporterAddr;
    String location;

    String inputSnmpIfName;
    String outputSnmpIfName;

    double packets;
    double bytes;

    public static Map<Instant, Bucket> bucketize(final EnrichedFlow flow, final Duration samplingInterval) {
        if (!samplingInterval.isPositive()) {
            throw new IllegalArgumentException("Sampling interval must be positive");
        }

        if (flow.getDeltaSwitched().isAfter(flow.getLastSwitched())) {
            throw new IllegalArgumentException("Flow has negative duration");
        }

        final var result = new TreeMap<Instant, Bucket>();

        final var builder = Bucket.builder()
                .srcAs(flow.getSrcAs())
                .dstAs(flow.getDstAs())
                .srcAddress(flow.getSrcAddr())
                .dstAddress(flow.getDstAddr())
                .srcHostname(flow.getSrcAddrHostname())
                .dstHostname(flow.getDstAddrHostname())
                .application(flow.getApplication())
                .exporterAddr(flow.getExporterAddr())
                .location(flow.getLocation())
                .inputSnmpIfName(flow.getInputSnmpIfName())
                .outputSnmpIfName(flow.getOutputSnmpIfName());

        final var firstBucket = LongMath.divide(flow.getDeltaSwitched().toEpochMilli(), samplingInterval.toMillis(), RoundingMode.FLOOR);
        final var lastBucket = LongMath.divide(flow.getLastSwitched().toEpochMilli(), samplingInterval.toMillis(), RoundingMode.CEILING);

        final var buckets = lastBucket - firstBucket;
        if (buckets == 1) {
            // Flow fits into single bucket
            final var bucket = Instant.ofEpochMilli((firstBucket + 1) * samplingInterval.toMillis());

            return Map.of(bucket, builder
                    .packets(flow.getPackets())
                    .bytes(flow.getBytes())
                    .build());
        }

        final var flowDuration = Duration.between(flow.getDeltaSwitched(), flow.getLastSwitched());

        for (int i = 0; i < buckets; i++) {
            final var bucketEnd = Instant.ofEpochMilli((firstBucket + i + 1) * samplingInterval.toMillis());
            final var bucketStart = bucketEnd.minus(samplingInterval);

            final double proportion;
            if (flow.getDeltaSwitched().isAfter(bucketStart)) {
                // Flow starts in bucket, but ends later
                final var part = Duration.between(flow.getDeltaSwitched(), bucketEnd);
                proportion = (double) part.toMillis() / (double) flowDuration.toMillis();

            } else if (flow.getLastSwitched().isBefore(bucketEnd)) {
                // Flow ends in bucket, but starts earlier
                final var part = Duration.between(bucketStart, flow.getLastSwitched());
                proportion = (double) part.toMillis() / (double) flowDuration.toMillis();

            } else {
                // Flow expands over the whole bucket
                proportion = (double) samplingInterval.toMillis() / (double) flowDuration.toMillis();
            }

            result.put(bucketEnd, builder
                    .packets(flow.getPackets() * proportion)
                    .bytes(flow.getBytes() * proportion)
                    .build());
        }

        return result;
    }
}
