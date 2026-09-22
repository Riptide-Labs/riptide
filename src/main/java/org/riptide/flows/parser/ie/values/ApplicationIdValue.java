/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

/**
 * IPFIX element 95, {@code applicationId}, RFC 6759 §4.1: one byte of Classification Engine ID
 * followed by a Selector ID of one to three bytes. The IANA registry types it as an octet array,
 * which nothing downstream can bind; this parser yields an {@link UnsignedValue} carrying the
 * packed form {@code engine << 24 | selector}, so a 4 byte field reads as its own big-endian
 * integer and shorter fields land in the same shape.
 *
 * <p>Lengths outside 2..4 yield {@code 0}, the "not sent" value, rather than an exception: a
 * template with an odd length must not cost the exporter its packets.</p>
 */
public final class ApplicationIdValue {

    public static final String NAME = "applicationId";

    private ApplicationIdValue() {
    }

    public static long packed(final int engineId, final long selectorId) {
        return ((long) (engineId & 0xFF) << 24) | (selectorId & 0xFFFFFFL);
    }

    public static InformationElement parser(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                final int length = buffer.readableBytes();
                if (length < 2 || length > 4) {
                    buffer.skipBytes(length);
                    return new UnsignedValue(name, semantics, unit, UnsignedLong.ZERO);
                }
                final int engineId = buffer.readUnsignedByte();
                long selectorId = 0;
                for (int i = 1; i < length; i++) {
                    selectorId = (selectorId << 8) | buffer.readUnsignedByte();
                }
                return new UnsignedValue(name, semantics, unit, UnsignedLong.valueOf(packed(engineId, selectorId)));
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
