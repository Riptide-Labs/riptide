/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported offset must be the failure position within the root PDU. The regression (#273): the
 * old length-based arithmetic assumed the failing slice ends where the PDU ends, so every interior
 * failure (e.g. a 4-byte set-header slice) was reported near the end of the message — production
 * logs showed {@code len - 2} for failures that actually happened at 0x50.
 */
class InvalidPacketExceptionTest {

    @Test
    void offsetOfInteriorSliceIsItsRealPosition() {
        final ByteBuf root = pdu(100);
        // a 4-byte "set header" slice at 0x50, failing after reading its 2-byte set id
        final ByteBuf setHeader = root.slice(0x50, 4);
        setHeader.readerIndex(2);

        final var e = new InvalidPacketException(setHeader, "Invalid set ID: %d", 0);

        assertThat(e.getMessage()).startsWith("Invalid set ID: 0, Offset: [0x0052]");
    }

    @Test
    void offsetOfNestedSliceIsItsRealPosition() {
        final ByteBuf root = pdu(100);
        // message slice (as the parser cuts the payload), then a set-header slice inside it
        final ByteBuf payload = root.slice(16, 84);
        final ByteBuf setHeader = payload.slice(0x40, 4);
        setHeader.readerIndex(2);

        final var e = new InvalidPacketException(setHeader, "Invalid set ID: %d", 0);

        assertThat(e.getMessage()).startsWith("Invalid set ID: 0, Offset: [0x0052]");
    }

    @Test
    void offsetOfRootBufferIsItsReaderIndex() {
        final ByteBuf root = pdu(100);
        root.readerIndex(7);

        final var e = new InvalidPacketException(root, "boom");

        assertThat(e.getMessage()).startsWith("boom, Offset: [0x0007]");
    }

    @Test
    void offsetOfDirectBufferSliceIsItsRealPosition() {
        // Production datagram buffers are direct (memoryAddress-based resolution).
        final ByteBuf root = Unpooled.directBuffer(100);
        for (int i = 0; i < 100; i++) {
            root.writeByte(i);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(root.hasMemoryAddress(),
                "direct buffers without memoryAddress on this platform");
        final ByteBuf setHeader = root.slice(0x50, 4);
        setHeader.readerIndex(2);

        final var e = new InvalidPacketException(setHeader, "Invalid set ID: %d", 0);

        assertThat(e.getMessage()).startsWith("Invalid set ID: 0, Offset: [0x0052]");
        root.release();
    }

    @Test
    void consumedRootStillDumpsFromByteZeroWithAbsoluteOffset() {
        // The parsers consume the root via readSlice, advancing its readerIndex; the dump must
        // still show the whole PDU from byte 0 and the offset must stay PDU-absolute.
        final ByteBuf root = pdu(100);
        root.readSlice(16); // e.g. the message header was consumed
        final ByteBuf setHeader = root.readSlice(64).slice(0x30, 4);
        setHeader.readerIndex(2);

        final var e = new InvalidPacketException(setHeader, "Invalid set ID: %d", 0);

        assertThat(e.getMessage()).startsWith("Invalid set ID: 0, Offset: [0x0042]");
        assertThat(e.getMessage()).contains("|00000000|");
    }

    @Test
    void dumpShowsTheWholePdu() {
        final ByteBuf root = pdu(64);
        final ByteBuf slice = root.slice(32, 4);

        final var e = new InvalidPacketException(slice, "boom");

        // 64 bytes -> four 16-byte hexdump rows of the full PDU, not just the failing slice
        assertThat(e.getMessage()).contains("|00000030|");
    }

    private static ByteBuf pdu(final int size) {
        final ByteBuf buffer = Unpooled.buffer(size);
        for (int i = 0; i < size; i++) {
            buffer.writeByte(i);
        }
        return buffer;
    }
}
