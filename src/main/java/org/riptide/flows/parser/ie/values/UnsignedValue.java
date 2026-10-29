/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.uint;

/**
 * An unsigned information element. The value is held as its 64 raw bits, read as unsigned, so
 * decoding a field allocates no {@link UnsignedLong} (#992). The numeric visitors, {@link #equals}
 * and {@link #hashCode} read {@link #bits()}. {@link #getValue()}, and so {@code UnsignedLongVisitor}
 * and {@link #toString()}, build a new {@link UnsignedLong} on each call.
 */
public class UnsignedValue extends Value<UnsignedLong> {
    private final long bits;

    private UnsignedValue(final String name,
                          final Semantics semantics,
                          final String unit,
                          final long bits) {
        super(name, semantics, unit);
        this.bits = bits;
    }

    /**
     * A value from raw unsigned bits, as {@code BufferUtils.uint} returns them: a negative
     * {@code bits} is a value at or above 2^63, not an error. For a signed {@code long} that must not
     * be negative, use {@link #UnsignedValue(String, long)}.
     */
    public static UnsignedValue fromBits(final String name,
                                         final Semantics semantics,
                                         final String unit,
                                         final long bits) {
        return new UnsignedValue(name, semantics, unit, bits);
    }

    public UnsignedValue(final String name,
                         final Semantics semantics,
                         final String unit,
                         final UnsignedLong value) {
        this(name, semantics, unit, Objects.requireNonNull(value).longValue());
    }

    /** A value from a signed {@code long}, which must not be negative, as {@code UnsignedLong.valueOf} demands. */
    public UnsignedValue(final String name,
                         final long value) {
        this(name, null, null, checkedNonNegative(value));
    }

    private static long checkedNonNegative(final long value) {
        Preconditions.checkArgument(value >= 0, "value (%s) is outside the range for an unsigned long value", value);
        return value;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("value", getValue())
                .toString();
    }

    public static InformationElement parserWith8Bit(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return fromBits(name, semantics, unit, uint(buffer, 1));
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
                return 1;
            }
        };
    }

    public static InformationElement parserWith16Bit(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return fromBits(name, semantics, unit, uint(buffer, buffer.readableBytes()));
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
                return 2;
            }
        };
    }

    public static InformationElement parserWith24Bit(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return fromBits(name, semantics, unit, uint(buffer, buffer.readableBytes()));
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
                return 3;
            }
        };
    }

    public static InformationElement parserWith32Bit(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return fromBits(name, semantics, unit, uint(buffer, buffer.readableBytes()));
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
                return 4;
            }
        };
    }

    public static InformationElement parserWith64Bit(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return fromBits(name, semantics, unit, uint(buffer, buffer.readableBytes()));
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
                return 8;
            }
        };
    }

    @Override
    public UnsignedLong getValue() {
        return UnsignedLong.fromLongBits(this.bits);
    }

    /** The value's 64 bits, read as unsigned. */
    public long bits() {
        return this.bits;
    }

    /**
     * {@link Value#equals} without the box. Options are looked up per data record by a set of scope
     * values, so this runs on the flow path. Another {@link Value} still compares through
     * {@link #getValue()}, as before.
     */
    @Override
    public boolean equals(final Object o) {
        if (o instanceof UnsignedValue other) {
            return this.bits == other.bits && Objects.equals(getName(), other.getName());
        }
        return super.equals(o);
    }

    /** {@code Objects.hash(name, getValue())} without the box: {@code UnsignedLong.hashCode} is {@code Long.hashCode} of the bits. */
    @Override
    public int hashCode() {
        return 31 * (31 + Objects.hashCode(getName())) + Long.hashCode(this.bits);
    }

    @Override
    public <X> X accept(ValueVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
