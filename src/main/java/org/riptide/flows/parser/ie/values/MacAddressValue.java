package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class MacAddressValue extends Value<byte[]> {
    public final byte[] value;

    public MacAddressValue(final String name,
                           final Semantics semantics,
                           final byte[] value) {
        super(name, semantics);
        this.value = Objects.requireNonNull(value);
    }

    public MacAddressValue(final String name, final byte[] value) {
        this(name, null, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("macAddressOctets", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new MacAddressValue(name, semantics, bytes(buffer, 6));
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
}
