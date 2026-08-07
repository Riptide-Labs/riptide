/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.session.Session;

import java.time.Instant;

import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * Fuzzes the NetFlow v9 parse surface, both stages: {@code Packet} framing/template installation and
 * the {@code Netflow9FlowBuilder} field decode that resolves data records against those templates.
 *
 * <p>Two harnesses, because NetFlow v9 is stateful. {@link #parse} exercises one packet against a
 * fresh session — pure framing bugs. {@link #sequence} splits the fuzzer input into several packets
 * driven through <em>one</em> session, which is where the interesting bugs live: a template installed
 * by an early packet and applied by a later data packet, template redefinition mid-stream, a data set
 * referencing a template that never arrived.
 */
class Netflow9ParserFuzzTest {

    private static final ValueConversionService CONVERSION = FuzzSupport.netflow9ConversionService();

    @FuzzTest
    void parse(final byte[] data) throws Throwable {
        runOne(FuzzSupport.newSession(), data);
    }

    @FuzzTest
    void sequence(final FuzzedDataProvider data) throws Throwable {
        final Session session = FuzzSupport.newSession();
        final int packets = data.consumeInt(2, 8);
        for (int i = 0; i < packets; i++) {
            // Length-prefixed chunks so the fuzzer controls the packet boundaries, not just the bytes.
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
            final Packet packet = new Packet(session, header, buffer);
            new Netflow9FlowBuilder(CONVERSION).buildFlows(Instant.EPOCH, packet).forEach(flow -> { });
        } catch (final Throwable t) {
            if (!FuzzSupport.isDesignedRejection(t)) {
                throw t;
            }
        }
    }
}
