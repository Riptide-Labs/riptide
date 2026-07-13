/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.sflow.proto;

import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.exceptions.InvalidPacketException;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint32;

/**
 * The XDR (type, length, payload) walk shared by sFlow's sample and flow-record lists
 * (sflow_version_5.txt §5.3): one bounds-checking implementation so a framing fix can
 * never apply to samples but not records. Vendor-enterprise entries (enterprise bits
 * nonzero) are skipped by length; the consumer sees only standard-enterprise formats.
 */
final class Xdr {

    interface EntryConsumer {
        void accept(int format, ByteBuf payload) throws InvalidPacketException;
    }

    private Xdr() {
    }

    static void walk(final ByteBuf buffer,
                     final long count,
                     final String what,
                     final EntryConsumer consumer) throws InvalidPacketException {
        for (long i = 0; i < count; i++) {
            if (buffer.readableBytes() < 8) {
                throw new InvalidPacketException(buffer, "Truncated %s %d of %d", what, i + 1, count);
            }
            final long dataFormat = uint32(buffer);
            final long length = uint32(buffer);
            if (length > buffer.readableBytes()) {
                throw new InvalidPacketException(buffer, "Invalid %s length: %d", what, length);
            }
            final ByteBuf payload = slice(buffer, (int) length);

            if ((dataFormat >>> 12) != 0) {
                continue; // vendor-specific: skip by length
            }
            consumer.accept((int) (dataFormat & 0xFFF), payload);
        }
    }
}
