/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.exceptions;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

public class InvalidPacketException extends Exception {

    public InvalidPacketException(final ByteBuf buffer, final String fmt, final Object... args) {
        super(appendPosition(String.format(fmt, args), buffer));
    }

    public InvalidPacketException(final ByteBuf buffer, final String message, final Throwable cause) {
        super(appendPosition(message, buffer), cause);
    }

    private static String appendPosition(final String message, final ByteBuf buffer) {
        // We want to hex-dump the whole PDU, so we need to get the unsliced buffer
        final ByteBuf root = rootOf(buffer);
        final ByteBuf dump = Unpooled.wrappedUnmodifiableBuffer(root).resetReaderIndex();

        final int offset = absoluteOffset(buffer, root);
        final String position = offset >= 0 ? String.format("0x%04X", offset) : "unknown";

        return String.format("%s, Offset: [%s], Payload:%n%s", message, position, ByteBufUtil.prettyHexDump(dump));
    }

    private static ByteBuf rootOf(final ByteBuf buffer) {
        ByteBuf root = buffer;
        while (root.unwrap() != null) {
            root = root.unwrap();
        }
        return root;
    }

    /**
     * The failure position within the root PDU. Netty slices expose their base through
     * {@code memoryAddress()}/{@code arrayOffset()}, so the slice's absolute start is recovered by
     * differencing against the root — correct for interior slices, where inferring the position
     * from lengths is not (a slice does not generally end where the PDU ends; the old length-based
     * arithmetic pinned every set-header failure near the end of the message). Returns -1 when the
     * buffers share no addressable memory (e.g. composite buffers).
     */
    private static int absoluteOffset(final ByteBuf buffer, final ByteBuf root) {
        final int offset;
        if (buffer == root) {
            offset = buffer.readerIndex();
        } else if (buffer.hasMemoryAddress() && root.hasMemoryAddress()) {
            offset = (int) (buffer.memoryAddress() - root.memoryAddress()) + buffer.readerIndex();
        } else if (buffer.hasArray() && root.hasArray() && buffer.array() == root.array()) {
            offset = buffer.arrayOffset() - root.arrayOffset() + buffer.readerIndex();
        } else {
            return -1;
        }
        // A violated derived-buffer assumption must degrade to "unknown", not a bogus position.
        return offset >= 0 && offset <= root.capacity() ? offset : -1;
    }
}
