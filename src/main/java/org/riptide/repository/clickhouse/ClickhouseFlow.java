package org.riptide.repository.clickhouse;

import lombok.Data;

import java.net.Inet6Address;
import java.sql.Timestamp;
import java.time.Duration;


@Data
public class ClickhouseFlow {
    private Timestamp receivedAt;
    private Timestamp timestamp;
    private long bytes;
    private byte direction;
    private Inet6Address dstAddr;
    private String dstAddrHostname;
    private long dstAs;
    private int dstMaskLen;
    private int dstPort;
    private int engineId;
    private int engineType;
    private Timestamp deltaSwitched;
    private Timestamp firstSwitched;
    private int flowRecords;
    private long flowSeqNum;
    private int inputSnmp;
    private int ipProtocolVersion;
    private Timestamp lastSwitched;
    private Inet6Address nextHop;
    private String nextHopHostname;
    private int outputSnmp;
    private long packets;
    private int protocol;
    private byte samplingAlgorithm = 0;
    private double samplingInterval = 1.0;
    private Inet6Address srcAddr;
    private String srcAddrHostname;
    private long srcAs;
    private int srcMaskLen;
    private int srcPort;
    private int tcpFlags;
    private int tos;
    private byte flowProtocol;
    private int vlan;

    private String application;
    private String exporterAddr;
    private String location;
    private byte srcLocality;
    private byte dstLocality;
    private byte flowLocality;
    private Duration clockCorrection;
    private String inputSnmpIfName;
    private String outputSnmpIfName;

    public String convoKey;

    public int dscp;
    public int ecn;
}
