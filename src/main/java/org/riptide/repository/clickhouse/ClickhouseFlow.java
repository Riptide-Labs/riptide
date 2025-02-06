package org.riptide.repository.clickhouse;

import lombok.Data;

import java.net.Inet6Address;
import java.sql.Timestamp;
import java.time.Duration;


@Data
public class ClickhouseFlow {
    private Timestamp timestamp;

    private byte flowProtocol;

    private String location;
    private String exporterAddr;

    private Timestamp receivedAt;

    private Timestamp firstSwitched;
    private Timestamp deltaSwitched;
    private Timestamp lastSwitched;

    private int inputSnmp;
    private String inputSnmpIfName;

    private int outputSnmp;
    private String outputSnmpIfName;

    private long srcAs;
    private Inet6Address srcAddr;
    private int srcMaskLen;
    private String srcAddrHostname;
    private int srcPort;

    private long dstAs;
    private Inet6Address dstAddr;
    private int dstMaskLen;
    private String dstAddrHostname;
    private int dstPort;

    private Inet6Address nextHop;
    private String nextHopHostname;

    private long bytes;
    private long packets;

    private byte direction;

    private int engineId;
    private int engineType;

    private int vlan;
    private int ipProtocolVersion;
    private int protocol;
    private int tcpFlags;
    private int tos;

    private byte samplingAlgorithm;
    private double samplingInterval = 1.0;

    private String application;

    private byte srcLocality;
    private byte dstLocality;
    private byte flowLocality;

    private Duration clockCorrection;
}
