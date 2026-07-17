/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import lombok.Builder;
import lombok.Data;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.data.Flow.Direction;
import org.riptide.flows.parser.data.Flow.FlowProtocol;
import org.riptide.flows.parser.data.Flow.Locality;
import org.riptide.flows.parser.data.Flow.SamplingAlgorithm;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;


@Data
@Builder
public class EnrichedFlow {
    private Instant receivedAt;
    private Instant timestamp;
    private Long bytes;
    private Direction direction;
    private InetAddress dstAddr;
    private String dstAddrHostname;
    private Long dstAs;
    private String dstAsOrg;
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
    private String srcAsOrg;
    private Integer srcMaskLen;
    private Integer srcPort;
    private Integer tcpFlags;
    private Integer tos;
    private FlowProtocol flowProtocol;
    private Integer vlan;

    private String application;
    private String exporterAddr;
    private String tenant;
    private String organisation;
    private String zone;
    private String system;
    private Locality srcLocality;
    private Locality dstLocality;
    private Locality flowLocality;
    private Duration clockCorrection;
    private String inputSnmpIfName;
    private String inputSnmpIfAlias;
    private Long inputSnmpIfSpeed;
    private String outputSnmpIfName;
    private String outputSnmpIfAlias;
    private Long outputSnmpIfSpeed;
    private String srcCountry;
    private String srcCity;
    private String dstCountry;
    private String dstCity;
    private String exporterName;

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
        @Mapping(target = "inputSnmpIfAlias", ignore = true)
        @Mapping(target = "inputSnmpIfSpeed", ignore = true)
        @Mapping(target = "outputSnmpIfName", ignore = true)
        @Mapping(target = "outputSnmpIfAlias", ignore = true)
        @Mapping(target = "outputSnmpIfSpeed", ignore = true)
        @Mapping(target = "srcAsOrg", ignore = true)
        @Mapping(target = "dstAsOrg", ignore = true)
        @Mapping(target = "srcAddrHostname", ignore = true)
        @Mapping(target = "dstAddrHostname", ignore = true)
        @Mapping(target = "nextHopHostname", ignore = true)
        @Mapping(target = "srcCountry", ignore = true)
        @Mapping(target = "srcCity", ignore = true)
        @Mapping(target = "dstCountry", ignore = true)
        @Mapping(target = "dstCity", ignore = true)
        @Mapping(target = "exporterName", ignore = true)

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
