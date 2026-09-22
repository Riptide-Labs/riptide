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
 * Cisco AVC HTTP URI statistics, enterprise element PEN 9 / 9357 (Cisco's 42125 with the
 * enterprise bit set): a sequence of {@code URI NUL count} pairs, the count two bytes in network
 * byte order, no trailing delimiter. Cisco's 2015 field guide gives the delimiter and the count
 * width; the byte order and the absent trailing delimiter were read from a Catalyst 8000V on
 * IOS-XE 26.01.02, which also records the first path segment only ({@code /api/login} arrives as
 * {@code /api}) and sends one pair per record.
 *
 * <p>One column holds one URI, so this yields the URI of the pair with the highest count, the
 * first on a tie. A trailing fragment without its NUL or its count is a malformed tail and is
 * ignored; a field with no complete pair yields the empty string, which is what every egress
 * record carries. The whole field is consumed whatever its shape.</p>
 */
public final class HttpUriStatisticsValue {

    public static final String NAME = "httpUri";

    private static final int COUNT_LENGTH = 2;

    private HttpUriStatisticsValue() {
    }

    public static InformationElement parser(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                String best = "";
                int bestHits = -1;
                while (buffer.isReadable()) {
                    final int nul = buffer.bytesBefore((byte) 0);
                    if (nul < 0 || buffer.readableBytes() < nul + 1 + COUNT_LENGTH) {
                        buffer.skipBytes(buffer.readableBytes());
                        break;
                    }
                    final String uri = new String(bytes(buffer, nul), StringValue.UTF8_CHARSET);
                    buffer.skipBytes(1);
                    final int hits = buffer.readUnsignedShort();
                    if (hits > bestHits) {
                        best = uri;
                        bestHits = hits;
                    }
                }
                return new StringValue(name, semantics, unit, best);
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
