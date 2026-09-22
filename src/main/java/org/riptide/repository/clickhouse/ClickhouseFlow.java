/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import lombok.Data;
import org.riptide.pipeline.ApplicationSource;
import org.riptide.flows.parser.data.Flow;

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

    // Pairs with the 1.0 above: an unenriched flow carries no resolution, and "assumed" is the
    // honest reading of a 1.0 nothing stated. '' is reserved for rows written before the column
    // existed, which is a different fact and is not something the collector can produce.
    private String samplingProvenance = Flow.SamplingProvenance.Assumed.token();

    private String application;

    // UInt32 on the wire; long so an engine id above 127 in the top byte never goes negative.
    private long applicationId;

    // 'none' for an unenriched flow; '' is reserved for rows written before the column existed.
    private String applicationSource = ApplicationSource.None.token();

    // '' for any flow the exporter's table did not describe, and for every row written before the
    // column existed; the two cannot be told apart and neither is backfilled.
    private String applicationDescription = "";

    // Cisco AVC HTTP host and URI, '' when the record carried none. Only the ingress record of an
    // HTTP request carries them, so the response record of the same conversation is '' too, as is
    // every row written before the columns existed.
    private String httpHost = "";
    private String httpUri = "";

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
    private String exporterName = "";
}
