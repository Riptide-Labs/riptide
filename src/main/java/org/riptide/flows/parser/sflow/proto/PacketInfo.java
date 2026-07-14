/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow.proto;

import com.google.common.base.MoreObjects;

import java.net.InetAddress;

/**
 * What could be decoded from a sampled packet header. Every field is nullable: headers
 * are truncated at the sampler (typically 128 bytes), so decoding stops wherever the
 * bytes run out and the flow is built from whatever was recovered — never dropped.
 */
public final class PacketInfo {

    public InetAddress srcAddr;
    public InetAddress dstAddr;
    public Integer srcPort;
    public Integer dstPort;
    public Integer protocol;
    public Integer tcpFlags;
    public Integer tos;
    public Integer vlan;
    public Integer ipVersion;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("srcAddr", this.srcAddr)
                .add("dstAddr", this.dstAddr)
                .add("srcPort", this.srcPort)
                .add("dstPort", this.dstPort)
                .add("protocol", this.protocol)
                .add("tcpFlags", this.tcpFlags)
                .add("tos", this.tos)
                .add("vlan", this.vlan)
                .add("ipVersion", this.ipVersion)
                .toString();
    }
}
