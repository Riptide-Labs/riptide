package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class StringValue extends Value<String> {
    public static final Charset UTF8_CHARSET = StandardCharsets.UTF_8;

    private final String value;

    public StringValue(final String name,
                       final Semantics semantics,
                       final String unit,
                       final String value) {
        super(name, semantics, unit);
        this.value = Objects.requireNonNull(value);
    }

    public StringValue(final String name, String value) {
        this(name, null, null, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("value", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new StringValue(name, semantics, unit, new String(bytes(buffer, buffer.readableBytes()), UTF8_CHARSET));
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
    public <X> X accept(ValueVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
