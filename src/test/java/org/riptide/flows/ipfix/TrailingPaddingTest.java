/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.ipfix;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * Some exporters pad the IPFIX message (e.g. to a 4-byte boundary), leaving a few trailing bytes
 * after the last Set. The parser must ignore them instead of reading them as a Set (Set ID 0),
 * which previously threw {@code Invalid set ID: 0} and dropped the whole packet.
 */
public class TrailingPaddingTest {

    // Header(16) + Template Set(12) + Data Set(12) + 2 padding bytes = 42 bytes. The header length
    // field (42) includes the padding, as a real exporter reports it.
    private static final byte[] MESSAGE = {
            // --- Message header ---
            0x00, 0x0a,                                     // version 10 (IPFIX)
            0x00, 0x2a,                                     // length 42 (incl. padding)
            0x00, 0x00, 0x00, 0x01,                         // export time
            0x00, 0x00, 0x00, 0x00,                         // sequence number
            0x00, 0x00, 0x00, 0x00,                         // observation domain
            // --- Template set (Set ID 2): template 256 = one octetDeltaCount (IE 1, 8 bytes) ---
            0x00, 0x02, 0x00, 0x0c, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x08,
            // --- Data set (Set ID 256): one record ---
            0x01, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a,
            // --- trailing message padding ---
            0x00, 0x00,
    };

    @Test
    void trailingPaddingDoesNotDropThePacket() throws Exception {
        final Session session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
        final ByteBuf buf = Unpooled.wrappedBuffer(MESSAGE);
        final var header = new Header(slice(buf, Header.SIZE));

        assertThatCode(() -> {
            final var packet = new Packet(session, header, slice(buf, header.length - Header.SIZE));
            // The data set parsed — previously the whole packet was dropped with "Invalid set ID: 0".
            assertThat(packet.dataRecordCount()).isEqualTo(1);
        }).doesNotThrowAnyException();
    }
}
