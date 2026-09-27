/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import java.util.Arrays;

/**
 * A hand-written snappy <em>block</em> encoder (not the streaming framing format): a varint
 * preamble carrying the uncompressed length, then a greedy LZ77 pass emitting literal and copy
 * tags. Written so the remote-write sink's runtime dependency graph never reaches {@code
 * sun.misc.Unsafe}: every published Snappy implementation checked for this project (both
 * {@code io.airlift:aircompressor} and {@code aircompressor-v3}) implements the algorithm through
 * {@code sun.misc.Unsafe}'s memory-access methods, deprecated for removal under JEP 471. The
 * block format is four tag shapes and one preamble, so writing it by hand costs less than
 * auditing a dependency for an API it might still be using two JDK releases from now, in keeping
 * with {@code PrometheusExposition}'s policy of not pulling a bridge library for a small format.
 *
 * <p><b>Literal tags.</b> A literal run of length 1..60 is one tag byte, {@code (length - 1) << 2}.
 * 61..256 is the marker byte {@code 60 << 2} followed by one length byte, {@code length - 1}.
 * 257..65536 is the marker byte {@code 61 << 2} followed by two little-endian length bytes,
 * {@code length - 1}. A single run longer than 65536 is emitted as several such tags back to
 * back, so the 3- and 4-byte extra-length forms the format also defines are never needed at any
 * input size. {@link #compress} refuses an input over {@link #MAX_INPUT_LENGTH} for a different
 * reason. That ceiling is a chosen bound, far above any remote-write batch this sink builds, not
 * a limit of the tag forms.
 *
 * <p><b>Copy tags.</b> Every copy this encoder emits carries a 2-byte offset (the 1- and 4-byte
 * offset copy tags are never produced): length 4..64, tag byte {@code ((length - 1) << 2) | 2},
 * followed by the offset as two little-endian bytes. A match longer than 64 bytes is split into
 * chunks of 64 with a final chunk of at least 4, since a copy tag cannot encode a length outside
 * 4..64.
 *
 * <p>The hash table has {@code 2^14} entries, keyed by a multiplicative hash of each 4-byte
 * window, storing the last position seen at that hash. It is one fixed table over the whole
 * input. The reference implementation instead compresses in 64 KiB fragments, each with its own
 * table sized to the fragment, so the two produce different, equally valid blocks. A collision (same hash, different 4 bytes) is simply not a match; nothing
 * chains or probes.
 */
public final class SnappyBlock {

    /**
     * A chosen bound on one remote-write batch, not a limit of the tag forms: literals are chunked
     * at 65536, so no input size needs the 3- or 4-byte extra-length forms (see the class javadoc).
     */
    private static final int MAX_INPUT_LENGTH = 1 << 24;

    private static final int HASH_TABLE_BITS = 14;
    private static final int HASH_TABLE_SIZE = 1 << HASH_TABLE_BITS;

    /** An arbitrary odd multiplier spreading a 4-byte window's bits across a 32-bit product. */
    private static final int HASH_MULTIPLIER = 0x1e35a7bd;

    private static final int MIN_MATCH_LENGTH = 4;
    private static final int MAX_OFFSET = 65_535;

    private static final int MAX_LITERAL_CHUNK = 65_536;
    private static final int LITERAL_ONE_BYTE_LENGTH_MARKER = 60;
    private static final int LITERAL_TWO_BYTE_LENGTH_MARKER = 61;
    private static final int LITERAL_ONE_BYTE_LENGTH_MAX = 256;

    private static final int MIN_COPY_LENGTH = 4;
    private static final int MAX_COPY_LENGTH = 64;
    private static final int COPY_TWO_BYTE_OFFSET_TYPE = 2;

    private SnappyBlock() {
    }

    /**
     * Encodes {@code input} as a complete snappy block: the varint preamble plus every tag.
     * Empty input yields the single preamble byte {@code 0}.
     *
     * @throws IllegalArgumentException if {@code input.length > MAX_INPUT_LENGTH}
     */
    public static byte[] compress(final byte[] input) {
        final int n = input.length;
        if (n > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException(
                    "SnappyBlock.compress refuses input over " + MAX_INPUT_LENGTH + " bytes (got " + n
                            + "). That is a chosen bound on one remote-write batch.");
        }

        final byte[] output = new byte[32 + n + n / 6];
        int outPos = writeVarint(output, 0, n);
        if (n == 0) {
            return Arrays.copyOf(output, outPos);
        }

        final int[] table = new int[HASH_TABLE_SIZE];
        Arrays.fill(table, -1);

        final int matchLimit = n - MIN_MATCH_LENGTH;
        int literalStart = 0;
        int ip = 0;
        while (ip <= matchLimit) {
            final int fourBytes = fourBytesAt(input, ip);
            final int hash = hash(fourBytes);
            final int candidate = table[hash];
            table[hash] = ip;
            if (candidate >= 0 && ip - candidate <= MAX_OFFSET && fourBytesAt(input, candidate) == fourBytes) {
                int matchLength = MIN_MATCH_LENGTH;
                while (ip + matchLength < n && input[candidate + matchLength] == input[ip + matchLength]) {
                    matchLength++;
                }
                if (ip > literalStart) {
                    outPos = emitLiteral(output, outPos, input, literalStart, ip - literalStart);
                }
                outPos = emitMatch(output, outPos, ip - candidate, matchLength);
                ip += matchLength;
                literalStart = ip;
            } else {
                ip++;
            }
        }
        if (literalStart < n) {
            outPos = emitLiteral(output, outPos, input, literalStart, n - literalStart);
        }
        return Arrays.copyOf(output, outPos);
    }

    private static int fourBytesAt(final byte[] data, final int pos) {
        return (data[pos] & 0xFF)
                | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16)
                | ((data[pos + 3] & 0xFF) << 24);
    }

    private static int hash(final int fourBytes) {
        return (fourBytes * HASH_MULTIPLIER) >>> (32 - HASH_TABLE_BITS);
    }

    /** Emits one or more literal tags covering exactly {@code length} bytes from {@code input}. */
    private static int emitLiteral(final byte[] out, final int outPos, final byte[] input,
                                   final int start, final int length) {
        int pos = outPos;
        int from = start;
        int remaining = length;
        while (remaining > 0) {
            final int chunk = Math.min(remaining, MAX_LITERAL_CHUNK);
            final int lengthMinusOne = chunk - 1;
            if (chunk <= LITERAL_ONE_BYTE_LENGTH_MARKER) {
                out[pos++] = (byte) (lengthMinusOne << 2);
            } else if (chunk <= LITERAL_ONE_BYTE_LENGTH_MAX) {
                out[pos++] = (byte) (LITERAL_ONE_BYTE_LENGTH_MARKER << 2);
                out[pos++] = (byte) lengthMinusOne;
            } else {
                out[pos++] = (byte) (LITERAL_TWO_BYTE_LENGTH_MARKER << 2);
                out[pos++] = (byte) (lengthMinusOne & 0xFF);
                out[pos++] = (byte) ((lengthMinusOne >>> 8) & 0xFF);
            }
            System.arraycopy(input, from, out, pos, chunk);
            pos += chunk;
            from += chunk;
            remaining -= chunk;
        }
        return pos;
    }

    /**
     * Emits one or more 2-byte-offset copy tags covering exactly {@code matchLength} bytes at
     * {@code offset}. A chunk is 64 bytes, except the last one or two chunks are shortened
     * (never below {@link #MIN_COPY_LENGTH}) so no chunk falls in the 1..3 range a copy tag
     * cannot represent.
     */
    private static int emitMatch(final byte[] out, final int outPos, final int offset, final int matchLength) {
        int pos = outPos;
        int remaining = matchLength;
        while (remaining > 0) {
            final int chunk;
            if (remaining <= MAX_COPY_LENGTH) {
                chunk = remaining;
            } else if (remaining - MAX_COPY_LENGTH < MIN_COPY_LENGTH) {
                chunk = remaining - MIN_COPY_LENGTH;
            } else {
                chunk = MAX_COPY_LENGTH;
            }
            pos = emitCopy(out, pos, offset, chunk);
            remaining -= chunk;
        }
        return pos;
    }

    private static int emitCopy(final byte[] out, final int outPos, final int offset, final int length) {
        out[outPos] = (byte) (((length - 1) << 2) | COPY_TWO_BYTE_OFFSET_TYPE);
        out[outPos + 1] = (byte) (offset & 0xFF);
        out[outPos + 2] = (byte) ((offset >>> 8) & 0xFF);
        return outPos + 3;
    }

    private static int writeVarint(final byte[] out, final int outPos, final int value) {
        int pos = outPos;
        int v = value;
        while ((v & ~0x7F) != 0) {
            out[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[pos++] = (byte) v;
        return pos;
    }
}
