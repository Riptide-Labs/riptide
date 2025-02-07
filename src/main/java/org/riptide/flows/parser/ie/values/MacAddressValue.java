package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class MacAddressValue extends Value<byte[]> {
    public final byte[] value;

    public MacAddressValue(final String name,
                           final Semantics semantics,
                           final String unit,
                           final byte[] value) {
        super(name, semantics, unit);
        this.value = Objects.requireNonNull(value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("macAddressOctets", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new MacAddressValue(name, semantics, unit, bytes(buffer, 6));
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 6;
            }

            @Override
            public int getMaximumFieldLength() {
                return 6;
            }
        };
    }

    @Override
    public byte[] getValue() {
        return this.value;
    }

    @Override
    public <X> X accept(ValueVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
