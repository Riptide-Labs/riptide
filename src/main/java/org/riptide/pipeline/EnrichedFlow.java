package org.riptide.pipeline;

import lombok.Getter;
import lombok.Setter;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.riptide.flows.parser.data.Flow;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;


@Getter
@Setter
public class EnrichedFlow implements Flow {
    private Instant receivedAt;
    private Instant timestamp;
    private Long bytes;
    private Direction direction;
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
    private SamplingAlgorithm samplingAlgorithm;
    private Double samplingInterval;
    private InetAddress srcAddr;
    private String srcAddrHostname;
    private Long srcAs;
    private Integer srcMaskLen;
    private Integer srcPort;
    private Integer tcpFlags;
    private Integer tos;
    private FlowProtocol flowProtocol;
    private Integer vlan;

    private String application;
    private String exporterAddr;
    private String location;
    private Locality srcLocality;
    private Locality dstLocality;
    private Locality flowLocality;
    private Duration clockCorrection;
    private String inputSnmpIfName;
    private String outputSnmpIfName;

    public String getConvoKey() {
        return ConversationKey.Utils.getConvoKeyAsJsonString(
                this.getLocation(),
                this.getProtocol(),
                this.getSrcAddr().getHostAddress(),
                this.getDstAddr().getHostAddress(),
                this.getApplication()
        );
    }

    public Integer getDscp() {
        return getTos() != null ? getTos() >>> 2 : null;
    }

    public Integer getEcn() {
        return getTos() != null ? getTos() & 0x03 : null;
    }

    @Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
            componentModel = "spring")
    public abstract static class FlowMapper {
        @Mapping(target = "application", ignore = true)
        @Mapping(target = "srcLocality", ignore = true)
        @Mapping(target = "dstLocality", ignore = true)
        @Mapping(target = "flowLocality", ignore = true)
        @Mapping(target = "clockCorrection", ignore = true)
        @Mapping(target = "inputSnmpIfName", ignore = true)
        @Mapping(target = "outputSnmpIfName", ignore = true)
        @Mapping(target = "srcAddrHostname", ignore = true)
        @Mapping(target = "dstAddrHostname", ignore = true)
        @Mapping(target = "nextHopHostname", ignore = true)

        @Mapping(target = ".", source = "source")
        @Mapping(target = ".", source = "flow")
        public abstract EnrichedFlow enrichedFlow(Source source, Flow flow);

        protected String address(final InetAddress address) {
            if (address == null) {
                return null;
            }

            return address.getHostAddress();
        }
    }
}
