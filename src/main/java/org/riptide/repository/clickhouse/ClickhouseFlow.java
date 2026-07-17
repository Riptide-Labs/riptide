/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import lombok.Data;

import java.net.Inet6Address;
import java.time.Duration;
import java.time.OffsetDateTime;


@Data
public class ClickhouseFlow {
    // OffsetDateTime (UTC), not java.sql.Timestamp: the client-v2 encodes a Timestamp from its
    // JVM-local wall clock, shifting DateTime64 values by the host's UTC offset on a non-UTC host
    // (#276). An offset-carrying type serializes to an absolute instant regardless of host zone.
    private OffsetDateTime timestamp;

    private byte flowProtocol;

    private String tenant;
    private String organisation;
    private String zone;
    private String system;
    private String exporterAddr;

    private OffsetDateTime receivedAt;

    private OffsetDateTime firstSwitched;
    private OffsetDateTime deltaSwitched;
    private OffsetDateTime lastSwitched;

    private int inputSnmp;
    private String inputSnmpIfName;
    private String inputSnmpIfAlias;
    private Long inputSnmpIfSpeed;

    private int outputSnmp;
    private String outputSnmpIfName;
    private String outputSnmpIfAlias;
    private Long outputSnmpIfSpeed;

    private long srcAs;
    private String srcAsOrg;
    private Inet6Address srcAddr;
    private int srcMaskLen;
    private String srcAddrHostname;
    private int srcPort;

    private long dstAs;
    private String dstAsOrg;
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

    // '' = unknown; the initializers survive an unenriched flow because the mapper's
    // null-value check leaves the target untouched for null sources.
    private String srcCountry = "";
    private String srcCity = "";
    private String dstCountry = "";
    private String dstCity = "";
}
