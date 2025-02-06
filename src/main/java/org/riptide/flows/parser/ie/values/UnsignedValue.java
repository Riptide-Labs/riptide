package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.visitor.TheVisitor;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.uint;

public class UnsignedValue extends Value<UnsignedLong> {
    private final UnsignedLong value;

    public UnsignedValue(final String name,
                         final Semantics semantics,
                         final UnsignedLong value) {
        super(name, semantics);
        this.value = Objects.requireNonNull(value);
    }

    public UnsignedValue(final String name,
                         final long value) {
        this(name, null, UnsignedLong.valueOf(value));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("value", value)
                .toString();
    }

    public static InformationElement parserWith8Bit(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new UnsignedValue(name, semantics, uint(buffer, 1));
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

    public static InformationElement parserWith16Bit(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new UnsignedValue(name, semantics, uint(buffer, buffer.readableBytes()));
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

    public static InformationElement parserWith24Bit(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new UnsignedValue(name, semantics, uint(buffer, buffer.readableBytes()));
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

    public static InformationElement parserWith32Bit(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new UnsignedValue(name, semantics, uint(buffer, buffer.readableBytes()));
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

    public static InformationElement parserWith64Bit(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new UnsignedValue(name, semantics, uint(buffer, buffer.readableBytes()));
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
        return this.value;
    }

    @Override
    public <X> X accept(TheVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
