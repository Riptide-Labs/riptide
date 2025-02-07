package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.InformationElementDatabase;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class OctetArrayValue extends Value<byte[]> {
    public final byte[] value;

    public OctetArrayValue(final String name,
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
                .add("data", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics, final String unit) {
        return parserWithLimits(0, 0xFFFF).parser(name, semantics, unit);
    }

    public static InformationElementDatabase.ValueParserFactory parserWithLimits(final int minimum, final int maximum) {
        return (name, semantics, unit) -> new InformationElement() {
            @Override
            public Value<?> parse(Session.Resolver resolver, ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
                return new OctetArrayValue(name, semantics, unit, bytes(buffer, buffer.readableBytes()));
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return minimum;
            }

            @Override
            public int getMaximumFieldLength() {
                return maximum;
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
