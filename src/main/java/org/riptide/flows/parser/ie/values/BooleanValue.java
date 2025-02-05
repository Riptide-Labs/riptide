package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.uint8;

public class BooleanValue extends Value<Boolean> {
    private final boolean value;

    public BooleanValue(final String name,
                        final Semantics semantics,
                        final boolean value) {
        super(name, semantics);
        this.value = Objects.requireNonNull(value);
    }

    public BooleanValue(final String name, final boolean value) {
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
            public Value<?> parse(final Session.Resolver resolver,
                                  final ByteBuf buffer) throws InvalidPacketException {
                final int value = uint8(buffer);
                if (value < 1 || value > 2) {
                    throw new InvalidPacketException(buffer, "Illegal value '%d' for boolean type (only 1/true and 2/false allowed)", value);
                }

                return new BooleanValue(name, semantics, value == 1);
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

    @Override
    public Boolean getValue() {
        return this.value;
    }
}
