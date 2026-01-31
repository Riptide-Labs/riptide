package org.riptide.bucketize;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.time.api.DateTimes;
import net.jqwik.time.api.Times;
import org.junit.jupiter.api.Test;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.utils.Tuple;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Percentage.withPercentage;
import static org.riptide.pipeline.EnrichedFlow.EnrichedFlowBuilder;

class BucketTest {

    @Test
    void testFullRange() {
        final var flow = EnrichedFlow.builder()
                .firstSwitched(Instant.ofEpochSecond(203))
                .deltaSwitched(Instant.ofEpochSecond(203))
                .lastSwitched(Instant.ofEpochSecond(219))
                .packets(32L)
                .bytes(320L)
                .build();

        final var buckets = Bucket.bucketize(flow, Duration.ofSeconds(5));

        assertThat(buckets).containsOnlyKeys(
                Instant.ofEpochSecond(205),
                Instant.ofEpochSecond(210),
                Instant.ofEpochSecond(215),
                Instant.ofEpochSecond(220));

        assertThat(buckets.get(Instant.ofEpochSecond(205)).getBytes()).isEqualTo(40L);
        assertThat(buckets.get(Instant.ofEpochSecond(210)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(215)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(220)).getBytes()).isEqualTo(80L);
    }

    @Test
    void testHittingEnd() {
        final var flow = EnrichedFlow.builder()
                .firstSwitched(Instant.ofEpochSecond(203))
                .deltaSwitched(Instant.ofEpochSecond(203))
                .lastSwitched(Instant.ofEpochSecond(220))
                .packets(34L)
                .bytes(340L)
                .build();

        final var buckets = Bucket.bucketize(flow, Duration.ofSeconds(5));

        assertThat(buckets).containsOnlyKeys(
                Instant.ofEpochSecond(205),
                Instant.ofEpochSecond(210),
                Instant.ofEpochSecond(215),
                Instant.ofEpochSecond(220));

        assertThat(buckets.get(Instant.ofEpochSecond(205)).getBytes()).isEqualTo(40L);
        assertThat(buckets.get(Instant.ofEpochSecond(210)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(215)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(220)).getBytes()).isEqualTo(100L);
    }

    @Test
    void testHittingStart() {
        final var flow = EnrichedFlow.builder()
                .firstSwitched(Instant.ofEpochSecond(200))
                .deltaSwitched(Instant.ofEpochSecond(200))
                .lastSwitched(Instant.ofEpochSecond(219))
                .packets(38L)
                .bytes(380L)
                .build();

        final var buckets = Bucket.bucketize(flow, Duration.ofSeconds(5));

        assertThat(buckets).containsOnlyKeys(
                Instant.ofEpochSecond(205),
                Instant.ofEpochSecond(210),
                Instant.ofEpochSecond(215),
                Instant.ofEpochSecond(220));

        assertThat(buckets.get(Instant.ofEpochSecond(205)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(210)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(215)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(220)).getBytes()).isEqualTo(80L);
    }

    @Test
    void testHittingBoth() {
        final var flow = EnrichedFlow.builder()
                .firstSwitched(Instant.ofEpochSecond(200))
                .deltaSwitched(Instant.ofEpochSecond(200))
                .lastSwitched(Instant.ofEpochSecond(220))
                .packets(40L)
                .bytes(400L)
                .build();

        final var buckets = Bucket.bucketize(flow, Duration.ofSeconds(5));

        assertThat(buckets).containsOnlyKeys(
                Instant.ofEpochSecond(205),
                Instant.ofEpochSecond(210),
                Instant.ofEpochSecond(215),
                Instant.ofEpochSecond(220));

        assertThat(buckets.get(Instant.ofEpochSecond(205)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(210)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(215)).getBytes()).isEqualTo(100L);
        assertThat(buckets.get(Instant.ofEpochSecond(220)).getBytes()).isEqualTo(100L);
    }

    @Test
    void testSingleBucket() {
        final var flow = EnrichedFlow.builder()
                .firstSwitched(Instant.ofEpochSecond(201))
                .deltaSwitched(Instant.ofEpochSecond(201))
                .lastSwitched(Instant.ofEpochSecond(204))
                .packets(6L)
                .bytes(60L)
                .build();

        final var buckets = Bucket.bucketize(flow, Duration.ofSeconds(5));

        assertThat(buckets).containsOnlyKeys(Instant.ofEpochSecond(205));

        assertThat(buckets.get(Instant.ofEpochSecond(205)).getBytes()).isEqualTo(60L);
    }

    @Test
    void testSingleBucketWithZeroDuration() {
        final var flow = EnrichedFlow.builder()
                .firstSwitched(Instant.ofEpochSecond(201))
                .deltaSwitched(Instant.ofEpochSecond(201))
                .lastSwitched(Instant.ofEpochSecond(201))
                .packets(6L)
                .bytes(60L)
                .build();

        final var buckets = Bucket.bucketize(flow, Duration.ofSeconds(5));

        assertThat(buckets).containsOnlyKeys(Instant.ofEpochSecond(205));

        assertThat(buckets.get(Instant.ofEpochSecond(205)).getBytes()).isEqualTo(60L);
    }

    @Property
    void sumMatchesTotal(@ForAll("flow") final EnrichedFlow flow,
                         @ForAll("samplingInterval") final Duration samplingInterval) {
        final var buckets = Bucket.bucketize(flow, samplingInterval);

        final var bytes = buckets.values().stream().mapToDouble(Bucket::getBytes).sum();
        final var packets = buckets.values().stream().mapToDouble(Bucket::getPackets).sum();

        assertThat(bytes).isCloseTo(flow.getBytes(), withPercentage(0.1));
        assertThat(packets).isCloseTo(flow.getPackets(), withPercentage(0.1));
    }

    @Provide
    Arbitrary<Duration> samplingInterval() {
        return Times.durations()
                .between(Duration.ZERO, Duration.ofSeconds(30))
                .filter(Duration::isPositive);
    }

    @Provide
    Arbitrary<EnrichedFlow> flow() {
        final var span = Combinators.combine(
                        DateTimes.instants(),
                        Times.durations().between(Duration.ofSeconds(0), Duration.ofDays(100)))
                .as((start, duration) -> Tuple.of(start, start.plus(duration)));

        return Builders.withBuilder(EnrichedFlow::builder)
                .use(span).in((builder, s) -> builder
                        .firstSwitched(s.first())
                        .deltaSwitched(s.first())
                        .lastSwitched(s.second()))
                .use(Arbitraries.longs()).inSetter(EnrichedFlowBuilder::srcAs)
                .use(Arbitraries.longs()).inSetter(EnrichedFlowBuilder::dstAs)
                .use(inetAddress()).inSetter(EnrichedFlowBuilder::srcAddr)
                .use(inetAddress()).inSetter(EnrichedFlowBuilder::dstAddr)
                .use(Arbitraries.strings()).inSetter(EnrichedFlowBuilder::srcAddrHostname)
                .use(Arbitraries.strings()).inSetter(EnrichedFlowBuilder::dstAddrHostname)
                .use(Arbitraries.strings()).inSetter(EnrichedFlowBuilder::application)
                .use(Arbitraries.strings()).inSetter(EnrichedFlowBuilder::exporterAddr)
                .use(Arbitraries.strings()).inSetter(EnrichedFlowBuilder::location)
                .use(Arbitraries.strings()).inSetter(EnrichedFlowBuilder::inputSnmpIfName)
                .use(Arbitraries.strings()).inSetter(EnrichedFlowBuilder::outputSnmpIfName)
                .use(Arbitraries.longs().greaterOrEqual(0)).inSetter(EnrichedFlowBuilder::packets)
                .use(Arbitraries.longs().greaterOrEqual(0)).inSetter(EnrichedFlowBuilder::bytes)
                .build(EnrichedFlowBuilder::build);
    }

    @Provide
    Arbitrary<InetAddress> inetAddress() {
        return Arbitraries.bytes().array(byte[].class).ofSize(4).map(bytes -> {
            try {
                return InetAddress.getByAddress(bytes);
            } catch (final UnknownHostException e) {
                throw new RuntimeException(e);
            }
        });
    }
}