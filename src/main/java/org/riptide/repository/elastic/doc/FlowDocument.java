package org.riptide.repository.elastic.doc;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * Member variables are sorted by the value of the @SerializedName annotation.
 */
@Getter
@Setter
public class FlowDocument {
    private static final int DOCUMENT_VERSION = 1;

    /**
     * Flow timestamp in milliseconds.
     */
    @SerializedName("@timestamp")
    private long timestamp;

    /**
     * Applied clock correction im milliseconds.
     */
    @SerializedName("@clock_correction")
    private Long clockCorrection;

    /**
     * Schema version.
     */
    @SerializedName("@version")
    private Integer version = DOCUMENT_VERSION;

    /**
     * Exporter IP address.
     */
    @SerializedName("exporter_addr")
    private String exporterAddr;

    /**
     * Exported location.
     */
    @SerializedName("location")
    private String location;

    /**
     * Application name as determined by the
     * classification engine.
     */
    @SerializedName("netflow.application")
    private String application;

    /**
     * Number of bytes transferred in the flow.
     */
    @SerializedName("netflow.bytes")
    private Long bytes;

    /**
     * Key used to group and identify conversations
     */
    @SerializedName("netflow.convo_key")
    private String convoKey;

    /**
     * Direction of the flow (egress vs ingress)
     */
    @SerializedName("netflow.direction")
    private Direction direction;

    /**
     * Destination address.
     */
    @SerializedName("netflow.dst_addr")
    private String dstAddr;

    /**
     * Destination address hostname.
     */
    @SerializedName("netflow.dst_addr_hostname")
    private String dstAddrHostname;

    /**
     * Destination autonomous system (AS).
     */
    @SerializedName("netflow.dst_as")
    private Long dstAs;

    /**
     * Locality of the destination address (i.e. private vs public address)
     */
    @SerializedName("netflow.dst_locality")
    private Locality dstLocality;

    /**
     * The number of contiguous bits in the source address subnet mask.
     */
    @SerializedName("netflow.dst_mask_len")
    private Integer dstMaskLen;

    /**
     * Destination port.
     */
    @SerializedName("netflow.dst_port")
    private Integer dstPort;

    /**
     * Slot number of the flow-switching engine.
     */
    @SerializedName("netflow.engine_id")
    private Integer engineId;

    /**
     * Type of flow-switching engine.
     */
    @SerializedName("netflow.engine_type")
    private Integer engineType;

    /**
     * Unix timestamp in ms at which the first packet
     * associated with this flow was switched.
     */
    @SerializedName("netflow.first_switched")
    private Long firstSwitched;

    /**
     * Locality of the flow:
     * private if both the source and destination localities are private,
     * and public otherwise.
     */
    @SerializedName("netflow.flow_locality")
    private Locality flowLocality;

    /**
     * Number of flow records in the associated packet.
     */
    @SerializedName("netflow.flow_records")
    private int flowRecords;

    /**
     * Flow packet sequence number.
     */
    @SerializedName("netflow.flow_seq_num")
    private long flowSeqNum;

    /**
     * SNMP ifIndex
     */
    @SerializedName("netflow.input_snmp")
    private Integer inputSnmp;

    /**
     * SNMP ifName
     */
    @SerializedName("netflow.input_snmpIfName")
    private String inputSnmpIfName;

    /**
     * IPv4 vs IPv6
     */
    @SerializedName("netflow.ip_protocol_version")
    private Integer ipProtocolVersion;

    /**
     * Unix timestamp in ms at which the last packet
     * associated with this flow was switched.
     */
    @SerializedName("netflow.last_switched")
    private Long lastSwitched;

    /**
     * Next hop
     */
    @SerializedName("netflow.next_hop")
    private String nextHop;

    /**
     * Next hop hostname
     */
    @SerializedName("netflow.next_hop_hostname")
    private String nextHopHostname;

    /**
     * SNMP ifIndex
     */
    @SerializedName("netflow.output_snmp")
    private Integer outputSnmp;

    /**
     * SNMP ifName
     */
    @SerializedName("netflow.output_snmpIfName")
    private String outputSnmpIfName;

    /**
     * Number of packets in the flow
     */
    @SerializedName("netflow.packets")
    private Long packets;

    /**
     * IP protocol number i.e 6 for TCP, 17 for UDP
     */
    @SerializedName("netflow.protocol")
    private Integer protocol;

    /**
     * Sampling algorithm ID
     */
    @SerializedName("netflow.sampling_algorithm")
    private SamplingAlgorithm samplingAlgorithm;

    /**
     * Sampling interval
     */
    @SerializedName("netflow.sampling_interval")
    private Double samplingInterval;

    /**
     * Source address.
     */
    @SerializedName("netflow.src_addr")
    private String srcAddr;

    /**
     * Source address hostname.
     */
    @SerializedName("netflow.src_addr_hostname")
    private String srcAddrHostname;

    /**
     * Source autonomous system (AS).
     */
    @SerializedName("netflow.src_as")
    private Long srcAs;

    /**
     * Locality of the source address (i.e. private vs public address)
     */
    @SerializedName("netflow.src_locality")
    private Locality srcLocality;

    /**
     * The number of contiguous bits in the destination address subnet mask.
     */
    @SerializedName("netflow.src_mask_len")
    private Integer srcMaskLen;

    /**
     * Source port.
     */
    @SerializedName("netflow.src_port")
    private Integer srcPort;

    /**
     * TCP Flags.
     */
    @SerializedName("netflow.tcp_flags")
    private Integer tcpFlags;

    /**
     * Unix timestamp in ms at which the previous exported packet
     * associated with this flow was switched.
     */
    @SerializedName("netflow.delta_switched")
    private Long deltaSwitched;

    @SerializedName("netflow.tos")
    private Integer tos;

    @SerializedName("netflow.ecn")
    private Integer ecn;

    @SerializedName("netflow.dscp")
    private Integer dscp;

    @SerializedName("protocol")
    private FlowProtocol flowProtocol;

    /**
     * The set of all hosts that are involved in this flow. This should include at a minimum the src and dst IP
     * addresses and may also include host names for those IPs.
     */
    @SerializedName("hosts")
    public Set<String> getHosts() {
        return Set.of(this.srcAddr, this.dstAddr);
    }

    /**
     * VLAN Name.
     */
    @SerializedName("netflow.vlan")
    private String vlan;
}
