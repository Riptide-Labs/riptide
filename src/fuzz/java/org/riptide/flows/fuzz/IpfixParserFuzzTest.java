/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.session.Session;

import java.time.Instant;

import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * Fuzzes the IPFIX parse surface, both stages, mirroring {@code IpfixUdpParser.parse}: the payload is
 * sliced to the header's declared length (an attacker-controlled field — this is the framing that
 * pmacct 1.7.8's nfprobe gets wrong in the homelab), then {@code Packet} installs template/options
 * sets and {@code IpFixFlowBuilder} resolves data sets against them.
 *
 * <p>Two harnesses as for NetFlow v9: {@link #parse} for single-packet framing, {@link #sequence} for
 * the template-then-data state carried across packets in one session.
 */
class IpfixParserFuzzTest {

    private static final ValueConversionService CONVERSION = FuzzSupport.ipfixConversionService();

    @FuzzTest
    void parse(final byte[] data) throws Throwable {
        runOne(FuzzSupport.newSession(), data);
    }

    @FuzzTest
    void sequence(final FuzzedDataProvider data) throws Throwable {
        final Session session = FuzzSupport.newSession();
        final int packets = data.consumeInt(2, 8);
        for (int i = 0; i < packets; i++) {
            final byte[] packet = data.consumeBytes(data.consumeInt(0, 2048));
            if (packet.length == 0) {
                continue;
            }
            runOne(session, packet);
        }
    }

    private static void runOne(final Session session, final byte[] data) throws Throwable {
        if (data.length < Header.SIZE) {
            return;
        }
        try {
            final ByteBuf buffer = FuzzSupport.buffer(data);
            final Header header = new Header(slice(buffer, Header.SIZE));
            // The real parser slices to the declared payload length before constructing the packet.
            final Packet packet = new Packet(session, header, slice(buffer, header.payloadLength()));
            new IpFixFlowBuilder(CONVERSION).buildFlows(Instant.EPOCH, packet).forEach(flow -> { });
        } catch (final Throwable t) {
            if (!FuzzSupport.isDesignedRejection(t)) {
                throw t;
            }
        }
    }
}
