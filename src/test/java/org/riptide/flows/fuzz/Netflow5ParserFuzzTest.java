/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;

import java.time.Instant;

import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * Fuzzes the NetFlow v5 parse surface: header framing, the fixed-size record loop, and the
 * second-stage {@code buildFlows} field decode. NetFlow v5 is stateless (no templates), so a single
 * buffer per run is the whole attack surface — no sequence harness needed.
 */
class Netflow5ParserFuzzTest {

    @FuzzTest
    void parse(final byte[] data) throws Throwable {
        if (data.length < Header.SIZE) {
            return;
        }
        try {
            final ByteBuf buffer = FuzzSupport.buffer(data);
            final Header header = new Header(slice(buffer, Header.SIZE));
            final Packet packet = new Packet(header, buffer);
            // Terminate the lazy stream so the record field decode actually runs.
            packet.buildFlows(Instant.EPOCH).forEach(flow -> { });
        } catch (final Throwable t) {
            if (!FuzzSupport.isDesignedRejection(t)) {
                throw t;
            }
        }
    }
}
