/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import lombok.Data;

import java.net.Inet6Address;
import java.time.OffsetDateTime;

/**
 * One row of the {@code traffic} table ({@link org.riptide.schema.TrafficSchema}), built only by
 * {@link TrafficMapper}.
 *
 * <p>Field names are the column names in camelCase: the ClickHouse client matches
 * {@code getTimeStart} to {@code time_start} by dropping underscores and case. No field is ever
 * null once the mapper has built the row, because no column is {@code Nullable}.</p>
 */
@Data
public class TrafficRow {
    private String tenant;
    private String organisation;
    private String zone;
    private String system;

    private OffsetDateTime timeStart;
    private OffsetDateTime timeEnd;
    private OffsetDateTime flowStart;
    private OffsetDateTime timeReceived;

    private Inet6Address exporterIp;
    private String exporterName;
    private String flowProtocol;
    // UInt32 on the wire; long so a value above Integer.MAX_VALUE never goes negative.
    private long observationDomain;

    private long inIf;
    private String inIfName;
    private String inIfAlias;
    private long inIfSpeed;
    private long outIf;
    private String outIfName;
    private String outIfAlias;
    private long outIfSpeed;
    private String direction;

    private Inet6Address srcAddr;
    private int srcPort;
    private int srcMask;
    private long srcAs;
    private String srcAsName;
    private String srcCountry;
    private String srcCity;
    private String srcHost;
    private String srcLocality;
    private Inet6Address dstAddr;
    private int dstPort;
    private int dstMask;
    private long dstAs;
    private String dstAsName;
    private String dstCountry;
    private String dstCity;
    private String dstHost;
    private String dstLocality;
    private Inet6Address nextHop;

    private int ipVersion;
    private int proto;
    private int tcpFlags;
    private int tos;
    private int vlan;

    private String application;
    private String applicationSource;
    private long applicationId;
    private String httpHost;
    private String httpUri;

    private long bytes;
    private long packets;
    private long bytesReported;
    private long packetsReported;
    private double samplingRate;
    private String samplingSource;
}
