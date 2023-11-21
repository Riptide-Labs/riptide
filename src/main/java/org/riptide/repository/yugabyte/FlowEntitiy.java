package org.riptide.repository.yugabyte;

import lombok.Data;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.EnrichedFlow;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Table("flows")
@Data
public class FlowEntitiy implements Persistable<UUID> {

    @Id
    private UUID id;

    private Instant receivedAt;
    private Instant timestamp;
    private Long bytes;
    private Flow.Direction direction;
    private InetAddress dstAddr;
    private String dstAddrHostname;
    private Long dstAs;
    private Integer dstMaskLen;
    private Integer dstPort;
    private Integer engineId;
    private Integer engineType;
    private Instant deltaSwitched;
    private Instant firstSwitched;
    private int flowRecords;
    private long flowSeqNum;
    private Integer inputSnmp;
    private Integer ipProtocolVersion;
    private Instant lastSwitched;
    private InetAddress nextHop;
    private String nextHopHostname;
    private Integer outputSnmp;
    private Long packets;
    private Integer protocol;
    private Flow.SamplingAlgorithm samplingAlgorithm;
    private Double samplingInterval;
    private InetAddress srcAddr;
    private String srcAddrHostname;
    private Long srcAs;
    private Integer srcMaskLen;
    private Integer srcPort;
    private Integer tcpFlags;
    private Integer tos;
    private Flow.FlowProtocol flowProtocol;
    private Integer vlan;

    private String application;
    private String exporterAddr;
    private String location;
    private Flow.Locality srcLocality;
    private Flow.Locality dstLocality;
    private Flow.Locality flowLocality;
    private Duration clockCorrection;
    private String inputSnmpIfName;
    private String outputSnmpIfName;

    private String convoKey;

    private Integer dscp;
    private Integer ecn;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    @Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
            componentModel = "spring")
    public abstract static class FlowEntityMapper {

        @Mapping(target = "id", ignore = true)
        public abstract FlowEntitiy from(EnrichedFlow flow);
    }
}
