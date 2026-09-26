/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import io.airlift.compress.v3.snappy.SnappyJavaDecompressor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips {@link SnappyBlock#compress} through an independent decoder,
 * {@code io.airlift.compress.v3.snappy.SnappyJavaDecompressor} (test scope only, see the
 * {@code aircompressor.version} pom comment), so the encoder under test is never checked against
 * itself.
 */
class SnappyBlockTest {

    @Test
    void emptyInputDecodesToEmpty() {
        assertThat(decode(SnappyBlock.compress(new byte[0]))).isEmpty();
    }

    @Test
    void oneByte() {
        roundTrips(new byte[] {42});
    }

    @ParameterizedTest
    @ValueSource(ints = {59, 60, 61, 256, 257, 70_000})
    void literalTagBoundaries(final int size) {
        final byte[] input = new byte[size];
        new Random(size).nextBytes(input);
        roundTrips(input);
    }

    @Test
    void aLongOverlappingMatchSurvivesChunking() {
        final byte[] input = new byte[100_000];
        Arrays.fill(input, (byte) 'x');
        roundTrips(input);
    }

    /**
     * {@code emitMatch}'s remainder-folding branch (a plain 64-byte split would otherwise leave a
     * final chunk of 1..3 bytes, which a copy tag cannot represent) fires when a match longer
     * than 64 bytes leaves a remainder of 1, 2 or 3 after splitting into 64-byte chunks. The
     * 100,000-byte test above never lands there (its match length reduces to a remainder of 31). An
     * all-identical-byte input of length {@code matchLength + 1} produces exactly one match of
     * {@code matchLength} at offset 1 (one leading literal byte, then the whole rest self-copies),
     * so these four sizes hit match lengths 64, 65, 66 and 67 directly.
     */
    @ParameterizedTest
    @ValueSource(ints = {65, 66, 67, 68})
    void matchLengthNearTheChunkBoundaryRoundTrips(final int size) {
        final byte[] input = new byte[size];
        Arrays.fill(input, (byte) 'x');
        roundTrips(input);
    }

    @Test
    void labelShapedPayloadCompressesAndRoundTrips() {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            text.append("__name__ifHCInOctetsexporteredge-01ifIndex").append(i % 10)
                    .append("ifNameGi0/0/3tenantt1");
        }
        final byte[] input = text.toString().getBytes(StandardCharsets.UTF_8);

        final byte[] compressed = SnappyBlock.compress(input);

        assertThat(decode(compressed)).isEqualTo(input);
        assertThat(compressed.length).isLessThan(input.length / 3);
    }

    @Test
    void inputOverTheCeilingIsRefused() {
        final byte[] input = new byte[(1 << 24) + 1];
        assertThatThrownBy(() -> SnappyBlock.compress(input)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void roundTrips(final byte[] input) {
        final byte[] compressed = SnappyBlock.compress(input);
        assertThat(decode(compressed)).isEqualTo(input);
    }

    private static byte[] decode(final byte[] compressed) {
        final SnappyJavaDecompressor decompressor = new SnappyJavaDecompressor();
        final int length = decompressor.getUncompressedLength(compressed, 0);
        final byte[] out = new byte[length];
        final int n = decompressor.decompress(compressed, 0, compressed.length, out, 0, out.length);
        assertThat(n).isEqualTo(length);
        return out;
    }
}
