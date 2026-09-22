/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

import static org.riptide.flows.utils.BufferUtils.bytes;

/**
 * Cisco AVC HTTP host, enterprise element PEN 9 / 12235 (Cisco's field id 45003 is the same
 * number with the enterprise bit set). Not a bare hostname: the field starts with the four-byte
 * http application id and the two-byte sub-application id for the host, then the name. Read from
 * a Catalyst 8000V on IOS-XE 26.01.02, where every record carries the constant prefix
 * {@code 03 00 00 50 34 02} whatever the flow's own application id, and a record with no host
 * carries exactly those six bytes.
 *
 * <p>The prefix is a constant of the layout, not of the flow: it is http's own application id
 * and the host field's sub-application id. So it is matched, not skipped by length: a field that
 * starts with it yields whatever follows, and a field that does not is stored as sent. A
 * platform that sent a bare hostname therefore stores the hostname, and a platform with a
 * different prefix stores a value that visibly starts with control bytes, rather than a
 * plausible-looking hostname missing its first six characters. Whatever the field's shape, all
 * of it is consumed so an odd template cannot desync the record.</p>
 */
public final class HttpHostValue {

    public static final String NAME = "httpHost";

    /** applicationId 0x03000050 (http) then sub-application id 0x3402 (the host). */
    static final byte[] PREFIX = {0x03, 0x00, 0x00, 0x50, 0x34, 0x02};

    private HttpHostValue() {
    }

    public static InformationElement parser(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                if (startsWithPrefix(buffer)) {
                    buffer.skipBytes(PREFIX.length);
                }
                final byte[] host = bytes(buffer, buffer.readableBytes());
                return new StringValue(name, semantics, unit, new String(host, StringValue.UTF8_CHARSET));
            }

            private boolean startsWithPrefix(final ByteBuf buffer) {
                if (buffer.readableBytes() < PREFIX.length) {
                    return false;
                }
                for (int i = 0; i < PREFIX.length; i++) {
                    if (buffer.getByte(buffer.readerIndex() + i) != PREFIX[i]) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 0;
            }

            @Override
            public int getMaximumFieldLength() {
                return 0xFFFF;
            }
        };
    }
}
