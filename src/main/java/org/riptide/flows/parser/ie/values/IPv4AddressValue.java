package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class IPv4AddressValue extends Value<Inet4Address> {
    public final Inet4Address value;

    public IPv4AddressValue(final String name,
                            final Semantics semantics,
                            final Inet4Address value) {
        super(name, semantics);
        this.value = Objects.requireNonNull(value);
    }

    public IPv4AddressValue(final String name, final Inet4Address value) {
        this(name, null, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("inet4Address", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) throws InvalidPacketException {
                try {
                    return new IPv4AddressValue(name, semantics, (Inet4Address) Inet4Address.getByAddress(bytes(buffer, 4)));
                } catch (final UnknownHostException e) {
                    throw new InvalidPacketException(buffer, "Error parsing IPv4 value", e);
                }
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 4;
            }

            @Override
            public int getMaximumFieldLength() {
                return 4;
            }
        };
    }

    @Override
    public Inet4Address getValue() {
        return this.value;
    }
}
