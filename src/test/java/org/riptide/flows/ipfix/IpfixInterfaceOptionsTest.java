/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.ExporterInterfaceTable;
import org.riptide.snmp.IfInfo;
import org.riptide.snmp.SnmpCacheConfig;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * Drives an ingressInterface-scoped IPFIX option record through the real parse path
 * into the exporter interface table. This pins the IANA registry names
 * ({@code interfaceName}/{@code interfaceDescription}/{@code ingressInterface}) end to
 * end — a registry-XML drift breaks this test instead of production only.
 */
public class IpfixInterfaceOptionsTest {

    @Test
    public void ipfixIngressScopedOptionsReachTheTable() throws Exception {
        final SnmpCacheConfig cacheConfig = new SnmpCacheConfig();
        final ExporterInterfaceTable table = new ExporterInterfaceTable(cacheConfig, new MetricRegistry());
        final Session session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), table);

        final ByteBuf b = Unpooled.buffer();
        // message header: version, length (patched below), export time, sequence, domain
        b.writeShort(0x000A).writeShort(0).writeInt(1_700_000_000).writeInt(1).writeInt(7);
        // options template set (id 3): template 400, fieldCount 3, scopeFieldCount 1
        b.writeShort(3).writeShort(4 + 6 + 3 * 4);
        b.writeShort(400).writeShort(3).writeShort(1);
        b.writeShort(10).writeShort(4);  // scope: ingressInterface
        b.writeShort(82).writeShort(8);  // interfaceName
        b.writeShort(83).writeShort(8);  // interfaceDescription
        // data set for template 400: ifIndex 5, "ge-0/0/0", "core-up"
        b.writeShort(400).writeShort(4 + 20);
        b.writeInt(5);
        b.writeBytes("ge-0/0/0".getBytes());
        b.writeBytes("core-up\0".getBytes());
        b.setShort(2, b.readableBytes()); // patch message length

        final Header header = new Header(slice(b, Header.SIZE));
        new Packet(session, header, slice(b, header.payloadLength()));

        final var identity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), 7);
        assertThat(table.lookup(identity, 5)).contains(new IfInfo("ge-0/0/0", "core-up", null));
    }
}
