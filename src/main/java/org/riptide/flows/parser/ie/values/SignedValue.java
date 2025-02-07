package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.sint;

public class SignedValue extends Value<Long> {
    private final long value;

    public SignedValue(final String name,
                       final Semantics semantics,
                       final String unit,
                       final long value) {
        super(name, semantics, unit);
        this.value = value;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("value", value)
                .toString();
    }

    public static InformationElement parserWith8Bit(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new SignedValue(name, semantics, unit, sint(buffer, 1));
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
                return new SignedValue(name, semantics, unit, sint(buffer, buffer.readableBytes()));
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

    public static InformationElement parserWith32Bit(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new SignedValue(name, semantics, unit, sint(buffer, buffer.readableBytes()));
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
                return new SignedValue(name, semantics, unit, sint(buffer, buffer.readableBytes()));
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
    public Long getValue() {
        return this.value;
    }

    @Override
    public <X> X accept(ValueVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
