/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.netflow9;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.ExporterInterfaceTable;
import org.riptide.snmp.IfInfo;
import org.riptide.snmp.SnmpCacheConfig;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * Drives interface option records through the real v9/IPFIX parse path (Packet →
 * session.addOptions → option tap) into the exporter interface table. The Cisco
 * ASR9k case uses genuine captured packets — the fixture that confirmed the
 * system-scoped, ifIndex-as-field, description-only shape.
 */
public class InterfaceOptionsBlackboxTest {

    private static final Path FOLDER = Paths.get("src/test/resources/flows");

    private final ExporterInterfaceTable table = new ExporterInterfaceTable(config(), new MetricRegistry());

    private static SnmpCacheConfig config() {
        final SnmpCacheConfig config = new SnmpCacheConfig();
        config.setRetentionMs(60_000);
        return config;
    }

    private final Session session =
            new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32), this.table);

    private void parseV9(final ByteBuf buf) throws Exception {
        do {
            new Packet(this.session, new Header(slice(buf, Header.SIZE)), buf);
        } while (buf.isReadable());
    }

    @Test
    public void asr9kInterfaceTableReachesTheExporterTable() throws Exception {
        // real ASR9k capture: opt template 256 = system scope + INPUT_SNMP + IF_DESC(64)
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_opttpl256.dat"))));
        parseV9(Unpooled.wrappedBuffer(Files.readAllBytes(FOLDER.resolve("netflow9_test_cisco_asr9k_data256.dat"))));

        final var identity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), 2177);
        assertThat(this.table.lookup(identity, 74)).contains(new IfInfo(null, "TenGigE0_0_1_0", null));
        assertThat(this.table.lookup(identity, 162)).contains(new IfInfo(null, "Bundle-Ether2", null));
        assertThat(this.table.lookup(identity, 999)).isEmpty();
    }

    @Test
    public void craftedV9InterfaceScopedOptionsReachTheTable() throws Exception {
        // shape A: v9 options template scoped by SCOPE:INTERFACE with IF_NAME + IF_DESC
        final ByteBuf b = Unpooled.buffer();
        b.writeShort(9).writeShort(2).writeInt(1000).writeInt(1_700_000_000).writeInt(1).writeInt(42);
        // options template set: id=1, length incl. 4-byte set header
        b.writeShort(1).writeShort(22);
        b.writeShort(300).writeShort(4).writeShort(8); // templateId, scopeLen(1 spec), optionLen(2 specs)
        b.writeShort(2).writeShort(4);   // scope: Interface, 4 bytes
        b.writeShort(82).writeShort(8);  // IF_NAME, 8 bytes
        b.writeShort(83).writeShort(8);  // IF_DESC, 8 bytes
        // option data set: template 300, one record: ifIndex 7, "Eth1/0", "uplink"
        b.writeShort(300).writeShort(24);
        b.writeInt(7);
        b.writeBytes("Eth1/0\0\0".getBytes());
        b.writeBytes("uplink\0\0".getBytes());

        parseV9(b);

        final var identity = new ExporterIdentity.NetflowIpfix(InetAddress.getLoopbackAddress(), 42);
        assertThat(this.table.lookup(identity, 7)).contains(new IfInfo("Eth1/0", "uplink", null));
    }
}
