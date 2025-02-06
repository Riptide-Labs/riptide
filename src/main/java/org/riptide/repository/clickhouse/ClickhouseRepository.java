package org.riptide.repository.clickhouse;


import com.clickhouse.client.api.Client;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.intellij.lang.annotations.Language;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.NullValueCheckStrategy;
import org.riptide.config.ClickhouseConfig;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

@Slf4j
public class ClickhouseRepository implements FlowRepository {

    private final FlowMapper flowMapper;

    private final Client client;

    @SneakyThrows
    public ClickhouseRepository(final FlowMapper flowMapper,
                                final ClickhouseConfig config) {
        this.flowMapper = Objects.requireNonNull(flowMapper);

        this.client = new Client.Builder()
                .addEndpoint(config.endpoint)
                .setUsername(config.username)
                .setPassword(config.password)
                .setDefaultDatabase(config.database)
                .compressClientRequest(true)
                .compressServerResponse(true)
                .build();

        this.client.execute(DDL_FLOWS).get();
        this.client.execute(DDL_SAMPLES).get();

        this.client.register(ClickhouseFlow.class, this.client.getTableSchema("flows"));
    }

    @Override
    public void persist(final List<EnrichedFlow> flows) throws FlowException, IOException {
        try {
            // Persist raw flows
            this.client.insert("flows", flows.stream().map(this.flowMapper::flow).toList()).get();

        } catch (final InterruptedException | ExecutionException e) {
            throw new FlowException(e);
        }
    }

    @Language("ClickHouse")
    private static final String DDL_FLOWS = """
        CREATE OR REPLACE TABLE flows (
            timestamp DateTime64(3),
    
            flowProtocol Enum8(
                'NetflowV5' = 1,
                'NetflowV9' = 2,
                'IPFIX' = 3,
                'SFLOW' = 4
            ),
    
            location String,
            exporterAddr String,
    
            receivedAt DateTime64(9),
    
            firstSwitched DateTime64(9),
            deltaSwitched DateTime64(9),
            lastSwitched DateTime64(9),
    
            inputSnmp UInt32,
            inputSnmpIfName Nullable(String),
    
            outputSnmp UInt32,
            outputSnmpIfName Nullable(String),
    
            srcAs UInt64,
            srcAddr IPv6,
            srcMaskLen UInt8,
            srcAddrHostname Nullable(String),
            srcPort UInt16,
    
            dstAs UInt64,
            dstAddr IPv6,
            dstMaskLen UInt8,
            dstAddrHostname Nullable(String),
            dstPort UInt16,
    
            nextHop Nullable(IPv6),
            nextHopHostname Nullable(String),
    
            bytes UInt64,
            packets UInt64,
    
            direction Enum8('INGRESS' = 1, 'EGRESS' = 2, 'UNKNOWN' = 3),
    
            engineId UInt16,
            engineType UInt16,
    
            vlan UInt16,
            ipProtocolVersion UInt8,
            protocol UInt8,
            tcpFlags UInt8,
            tos UInt8,
    
            samplingAlgorithm Enum8(
                'Unassigned' = 0,
                'SystematicCountBasedSampling' = 1,
                'SystematicTimeBasedSampling' = 2,
                'RandomNOutOfNSampling' = 3,
                'UniformProbabilisticSampling' = 4,
                'PropertyMatchFiltering' = 5,
                'HashBasedFiltering' = 6,
                'FlowStateDependentIntermediateFlowSelectionProcess' = 7
            ),\s
            samplingInterval Float64,
    
            application Nullable(String),
    
            srcLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
            dstLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
            flowLocality Enum8('PUBLIC' = 1, 'PRIVATE' = 2),
    
            clockCorrection Nullable(Int64)
        ) ENGINE = MergeTree()
        ORDER BY (
            srcAs, dstAs,
            srcAddr, dstAddr,
            srcPort, dstPort,
            toUnixTimestamp(timestamp)
        )
        PARTITION BY toYYYYMMDD(timestamp)
        TTL toDateTime(timestamp) + INTERVAL 30 DAY
        SETTINGS index_granularity = 8192;
    """;

    @Language("ClickHouse")
    private static final String DDL_SAMPLES = """
        CREATE or REPLACE VIEW samples AS
        WITH
            toInt64({ival:Int64} * 1e9) AS interval_ns,
    
            -- Find first and last absolute bucket numbers
            toUInt64(toUnixTimestamp64Nano(deltaSwitched) / interval_ns) as first_bucket,
            toUInt64(toUnixTimestamp64Nano(lastSwitched) / interval_ns) as last_bucket,
    
            last_bucket - first_bucket + 1 as bucket_count,
    
            -- Calculate total duration in nanoseconds
            age('ns', deltaSwitched, lastSwitched) as flow_duration,
    
            -- Generate buckets from the first bucket onward
            range(toUInt32(bucket_count)) AS buckets,
    
            -- Determine the fraction of the interval used in each case
            CASE
                -- Only one bucket
                WHEN bucket_count = 1
                    THEN 1.0
    
                -- First bucket: Portion from start time to the next bucket boundary
                WHEN bucket = 0
                    THEN ((interval_ns * (first_bucket + 1)) - toUnixTimestamp64Nano(deltaSwitched)) / interval_ns
    
                -- Last bucket: Portion from the start of the last bucket to end time
                WHEN bucket = bucket_count - 1
                    THEN (toUnixTimestamp64Nano(lastSwitched) - (interval_ns * last_bucket)) / interval_ns
    
                -- Full buckets in between
                ELSE 1.0
                END AS bucket_fraction
    
        SELECT
            flow.*,
    
            fromUnixTimestamp64Nano((first_bucket + bucket) * interval_ns) AS timestamp,
    
            bytes * bucket_fraction / toFloat64(bucket_count) as bytes,
            packets * bucket_fraction / toFloat64(bucket_count) as packets
    
        FROM flows AS flow
        ARRAY JOIN buckets AS bucket
        ORDER BY timestamp;
    """;

    @Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
            componentModel = "spring")
    public abstract static class FlowMapper {
        @BeanMapping(ignoreUnmappedSourceProperties = {
                "convoKey",
                "dscp",
                "ecn",
                "flowRecords",
                "flowSeqNum",
        })
        public abstract ClickhouseFlow flow(EnrichedFlow flow);

        protected Timestamp timestamp(final Instant value) {
            return Timestamp.from(value);
        }

        @SneakyThrows
        protected Inet6Address address(final InetAddress value) {
            if (value instanceof Inet6Address v6) {
                return v6;
            }

            if (value instanceof Inet4Address v4) {
                final var d = v4.getAddress();
                return Inet6Address.getByAddress(null, new byte[]{
                        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                        (byte) 0x00, (byte) 0x00, (byte) 0xff, (byte) 0xff,
                        (byte) d[0], (byte) d[1], (byte) d[2], (byte) d[3],
                }, null);
            }

            return null;
        }

        protected byte direction(final Flow.Direction value) {
            return (byte) (value.ordinal() + 1);
        }

        protected byte samplingAlgorithm(final Flow.SamplingAlgorithm value) {
            return (byte) (value.ordinal() + 1);
        }

        protected byte protocol(final Flow.FlowProtocol value) {
            return (byte) (value.ordinal() + 1);
        }

        protected byte locality(final Flow.Locality value) {
            return (byte) (value.ordinal() + 1);
        }
    }
}
