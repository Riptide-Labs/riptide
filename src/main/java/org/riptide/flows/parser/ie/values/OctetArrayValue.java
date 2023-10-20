package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.MissingTemplateException;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.InformationElementDatabase;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

import java.util.Objects;
import java.util.Optional;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class OctetArrayValue extends Value<byte[]> {
    public final byte[] value;

    public OctetArrayValue(final String name,
                           final Semantics semantics,
                           final byte[] value) {
        super(name, semantics);
        this.value = Objects.requireNonNull(value);
    }

    public OctetArrayValue(final String name,
                           final byte[] value) {
        this(name, null, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("data", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics) {
        return parserWithLimits(0, 0xFFFF).parser(name, semantics);
    }

    public static InformationElementDatabase.ValueParserFactory parserWithLimits(final int minimum, final int maximum) {
        return (name, semantics) -> new InformationElement() {
            @Override
            public Value<?> parse(Session.Resolver resolver, ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
                return new OctetArrayValue(name, semantics, bytes(buffer, buffer.readableBytes()));
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
    public void visit(final Visitor visitor) {
        visitor.accept(this);
    }

    @Override
    public Typed typed() {
        return new Typed() {
            @Override
            public Optional<OctetArrayValue> asOctetArrayValue() {
                return Optional.of(OctetArrayValue.this);
            }
        };
    }
}
