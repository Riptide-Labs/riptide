/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import static org.riptide.flows.utils.BufferUtils.uint;
import static org.riptide.flows.utils.BufferUtils.uint64;

/**
 * Holds {@code BufferUtils.uint}, {@code uint64} and every reader of an {@link UnsignedValue} to
 * what they returned while {@code uint} still built a Guava {@link UnsignedLong} per field (#992).
 *
 * <p>The reference is {@link #referenceUint}, a copy of that implementation. This test passed
 * against the boxed {@code uint} before the change, so it holds the old behaviour, not the new
 * one's own reading of it. Values are built with {@link UnsignedValue#fromBits}, the path every
 * parser takes.</p>
 */
class BufferUtilsEquivalenceTest {

    /** {@code bits} values every reader must get right, including the unsigned upper half. */
    private static final long[] EDGES = {
        0L, 1L, 0x7FL, 0x80L, 0xFFL, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL,
        0x7FFFFFFFFFFFFFFFL, 0x8000000000000000L, 0x8000000000000001L, 0xC000000000000001L,
        0xFFFFFFFFFFFFF7FFL, 0xFFFFFFFFFFFFFC00L, 0xFFFFFFFFFFFFFFFFL,
    };

    /** The pre-#992 {@code BufferUtils.uint}, kept as the reference. */
    private static UnsignedLong referenceUint(final ByteBuf buffer, final int octets) {
        long result = 0;
        for (int i = 0; i < octets; i++) {
            result = (result << 8L) | (buffer.readUnsignedByte() & 0xFFL);
        }
        return UnsignedLong.fromLongBits(result);
    }

    private static List<byte[]> patterns() {
        final List<byte[]> patterns = new ArrayList<>();
        patterns.add(new byte[8]);
        final byte[] ones = new byte[8];
        Arrays.fill(ones, (byte) 0xFF);
        patterns.add(ones);
        for (int bit = 0; bit < 64; bit++) {
            final byte[] single = new byte[8];
            single[bit / 8] = (byte) (0x80 >>> (bit % 8));
            patterns.add(single);
        }
        final Random random = new Random(992);
        for (int i = 0; i < 200; i++) {
            final byte[] bytes = new byte[8];
            random.nextBytes(bytes);
            patterns.add(bytes);
        }
        return patterns;
    }

    private static ByteBuf buffer(final byte[] bytes, final boolean direct) {
        final ByteBuf buffer = direct ? Unpooled.directBuffer(bytes.length) : Unpooled.buffer(bytes.length);
        return buffer.writeBytes(bytes);
    }

    @Test
    void uintMatchesTheReferenceAtEveryWidthOnHeapAndDirectBuffers() {
        int compared = 0;
        for (final boolean direct : new boolean[] {false, true}) {
            for (final byte[] bytes : patterns()) {
                for (int width = 0; width <= 8; width++) {
                    final ByteBuf actual = buffer(bytes, direct);
                    final ByteBuf expected = buffer(bytes, direct);
                    final long bits = uint(actual, width);
                    Assertions.assertThat(bits)
                            .as("width %d, direct %s, bytes %s", width, direct, HexFormat.of().formatHex(bytes))
                            .isEqualTo(referenceUint(expected, width).longValue());
                    Assertions.assertThat(actual.readerIndex()).isEqualTo(width);
                    actual.release();
                    expected.release();
                    compared++;
                }
            }
        }
        Assertions.assertThat(compared).isEqualTo(2 * patterns().size() * 9);
    }

    @Test
    void uint64MatchesTheReferenceAtEightOctets() {
        for (final byte[] bytes : patterns()) {
            Assertions.assertThat(uint64(buffer(bytes, false)))
                    .isEqualTo(referenceUint(buffer(bytes, false), 8).longValue());
        }
    }

    @Test
    void aWidthOutsideZeroToEightIsRefused() {
        Assertions.assertThatThrownBy(() -> uint(buffer(new byte[16], false), -1))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThatThrownBy(() -> uint(buffer(new byte[16], false), 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static long[] allBits() {
        final Random random = new Random(992);
        final long[] bits = new long[EDGES.length + 200];
        System.arraycopy(EDGES, 0, bits, 0, EDGES.length);
        for (int i = EDGES.length; i < bits.length; i++) {
            bits[i] = random.nextLong();
        }
        return bits;
    }

    private static UnsignedValue value(final long bits, final String unit) {
        return UnsignedValue.fromBits("field", null, unit, bits);
    }

    @Test
    void numericVisitorsReturnWhatTheUnsignedLongMethodsReturn() {
        for (final long bits : allBits()) {
            final UnsignedLong reference = UnsignedLong.fromLongBits(bits);
            final UnsignedValue value = value(bits, null);
            Assertions.assertThat(value.accept(new LongVisitor())).as("long %x", bits).isEqualTo(reference.longValue());
            Assertions.assertThat(value.accept(new IntegerVisitor())).as("int %x", bits).isEqualTo(reference.intValue());
            Assertions.assertThat(Double.doubleToRawLongBits(value.accept(new DoubleVisitor())))
                    .as("double %x", bits).isEqualTo(Double.doubleToRawLongBits(reference.doubleValue()));
            Assertions.assertThat(value.accept(new UnsignedLongVisitor())).as("unsigned %x", bits).isEqualTo(reference);
        }
    }

    /** The value {@code call} returns, or the class of what it throws, so two calls compare in full. */
    private static Object outcome(final Supplier<?> call) {
        try {
            return call.get();
        } catch (final RuntimeException e) {
            return e.getClass();
        }
    }

    @Test
    void durationAndInstantVisitorsReadTheBitsAsLongValueDid() {
        for (final long bits : allBits()) {
            final long l = UnsignedLong.fromLongBits(bits).longValue();
            final Map<String, LongFunction<Object>> durations = Map.of(
                    "seconds", Duration::ofSeconds, "milliseconds", Duration::ofMillis,
                    "nanoseconds", Duration::ofNanos, "microseconds", n -> Duration.ofNanos(n * 1_000));
            durations.forEach((unit, expected) -> Assertions.assertThat(outcome(() -> value(bits, unit).accept(new DurationVisitor())))
                    .as("duration %s %x", unit, bits).isEqualTo(outcome(() -> expected.apply(l))));
            final Map<String, LongFunction<Object>> instants = Map.of(
                    "seconds", Instant::ofEpochSecond, "milliseconds", Instant::ofEpochMilli);
            instants.forEach((unit, expected) -> Assertions.assertThat(outcome(() -> value(bits, unit).accept(new InstantVisitor())))
                    .as("instant %s %x", unit, bits).isEqualTo(outcome(() -> expected.apply(l))));
        }
    }

    @Test
    void equalsHashCodeAndToStringAreUnchanged() {
        for (final long bits : allBits()) {
            final UnsignedLong reference = UnsignedLong.fromLongBits(bits);
            final UnsignedValue a = value(bits, null);
            final UnsignedValue b = value(bits, null);
            Assertions.assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            Assertions.assertThat(a.hashCode()).isEqualTo(Objects.hash("field", reference));
            // Both constructors agree, and either way a different name or different bits is a different value.
            final UnsignedValue boxed = new UnsignedValue("field", null, null, reference);
            Assertions.assertThat(a).isEqualTo(boxed).hasSameHashCodeAs(boxed);
            Assertions.assertThat(boxed).isEqualTo(a);
            Assertions.assertThat(a).isNotEqualTo(UnsignedValue.fromBits("other", null, null, bits));
            Assertions.assertThat(a).isNotEqualTo(UnsignedValue.fromBits("field", null, null, bits ^ 1));
            Assertions.assertThat(a.getValue()).isEqualTo(reference);
            Assertions.assertThat(a.toString()).isEqualTo("UnsignedValue{name=field, value=" + reference + "}");
        }
    }

    @Test
    void theConvenienceConstructorStillRefusesANegativeValue() {
        Assertions.assertThatThrownBy(() -> new UnsignedValue("field", -1L))
                .isInstanceOf(IllegalArgumentException.class);
        Assertions.assertThat(new UnsignedValue("field", Long.MAX_VALUE).getValue())
                .isEqualTo(UnsignedLong.valueOf(Long.MAX_VALUE));
    }
}
