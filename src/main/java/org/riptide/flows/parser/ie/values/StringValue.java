package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class StringValue extends Value<String> {
    public final static Charset UTF8_CHARSET = StandardCharsets.UTF_8;

    private final String value;

    public StringValue(final String name,
                       final Semantics semantics,
                       final String value) {
        super(name, semantics);
        this.value = Objects.requireNonNull(value);
    }

    public StringValue(final String name, final String value) {
        this(name, null, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("value", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new StringValue(name, semantics, new String(bytes(buffer, buffer.readableBytes()), UTF8_CHARSET));
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
                return 0xFFFF;
            }
        };
    }

    @Override
    public String getValue() {
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
            public Optional<StringValue> asStringValue() {
                return Optional.of(StringValue.this);
            }
        };
    }
}
