/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.utils;

import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.ByteBuf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.util.function.Function;

public final class BufferUtils {

    private BufferUtils() {
    }

    public static ByteBuf slice(final ByteBuf buffer, final int size) {
        // Sizes are derived from attacker-controlled length fields, so guard both ends: a negative
        // size slips past the readableBytes check and only fails later inside Netty.
        if (size < 0 || size > buffer.readableBytes()) {
            throw new BufferUnderflowException();
        }

        final ByteBuf result = buffer.slice(buffer.readerIndex(), size);
        buffer.readerIndex(buffer.readerIndex() + size);

        return result;
    }

    public static <R> R peek(final ByteBuf buffer, Function<ByteBuf, R> consumer) {
        final int position = buffer.readerIndex();
        try {
            return consumer.apply(buffer);
        } finally {
            buffer.readerIndex(position);
        }
    }

    public static float sfloat(final ByteBuf buffer) {
        return Float.intBitsToFloat(sint32(buffer));
    }

    public static UnsignedLong uint(final ByteBuf buffer, final int octets) {
        Preconditions.checkArgument(0 <= octets && octets <= 8);

        long result = 0;

        for (int i = 0; i < octets; i++) {
            result = (result << 8L) | (buffer.readUnsignedByte() & 0xFFL);
        }

        return UnsignedLong.fromLongBits(result);
    }

    public static Long sint(final ByteBuf buffer, final int octets) {
        Preconditions.checkArgument(0 <= octets && octets <= 8);

        long result = buffer.readUnsignedByte() & 0xFFL;
        boolean s = (result & 0x80L) != 0;
        if (s) {
            result = 0xFFFFFFFFFFFFFF80L | (result & 0x7FL);
        } else {
            result &= 0x7FL;
        }

        for (int i = 1; i < octets; i++) {
            result = (result << 8L) | (buffer.readUnsignedByte() & 0xFFL);
        }

        return result;
    }

    public static int uint8(final ByteBuf buffer) {
        return buffer.readUnsignedByte() & 0xFF;
    }

    public static int uint16(final ByteBuf buffer) {
        return ((buffer.readUnsignedByte() & 0xFF) << 8)
             | ((buffer.readUnsignedByte() & 0xFF) << 0);
    }

    public static int uint24(final ByteBuf buffer) {
        return ((buffer.readUnsignedByte() & 0xFF) << 16)
             | ((buffer.readUnsignedByte() & 0xFF) << 8)
             | ((buffer.readUnsignedByte() & 0xFF) << 0);
    }

    public static long uint32(final ByteBuf buffer) {
        return ((buffer.readUnsignedByte() & 0xFFL) << 24)
             | ((buffer.readUnsignedByte() & 0xFFL) << 16)
             | ((buffer.readUnsignedByte() & 0xFFL) << 8)
             | ((buffer.readUnsignedByte() & 0xFFL) << 0);
    }

    public static UnsignedLong uint64(final ByteBuf buffer) {
        return UnsignedLong.fromLongBits(
                ((buffer.readUnsignedByte() & 0xFFL) << 56)
              | ((buffer.readUnsignedByte() & 0xFFL) << 48)
              | ((buffer.readUnsignedByte() & 0xFFL) << 40)
              | ((buffer.readUnsignedByte() & 0xFFL) << 32)
              | ((buffer.readUnsignedByte() & 0xFFL) << 24)
              | ((buffer.readUnsignedByte() & 0xFFL) << 16)
              | ((buffer.readUnsignedByte() & 0xFFL) << 8)
              | ((buffer.readUnsignedByte() & 0xFFL) << 0));
    }

    public static Integer sint32(final ByteBuf buffer) {
        return ((buffer.readUnsignedByte() & 0xFF) << 24)
             | ((buffer.readUnsignedByte() & 0xFF) << 16)
             | ((buffer.readUnsignedByte() & 0xFF) << 8)
             | ((buffer.readUnsignedByte() & 0xFF) << 0);
    }

    public static byte[] bytes(final ByteBuf buffer, final int size) {
        final byte[] result = new byte[size];
        buffer.readBytes(result);
        return result;
    }

    /** Reads {@code octets} bytes (4 or 16) as an address. */
    public static InetAddress inetAddress(final ByteBuf buffer, final int octets) {
        Preconditions.checkArgument(octets == 4 || octets == 16);
        try {
            return InetAddress.getByAddress(bytes(buffer, octets));
        } catch (final UnknownHostException e) {
            // unreachable: getByAddress only rejects lengths other than 4 and 16
            throw new IllegalStateException(e);
        }
    }
}
