/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class IPv6AddressValue extends Value<Inet6Address> {
    public final Inet6Address value;

    public IPv6AddressValue(final String name,
                            final Semantics semantics,
                            final String unit,
                            final Inet6Address value) {
        super(name, semantics, unit);
        this.value = Objects.requireNonNull(value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("inet6Address", value)
                .toString();
    }

    public static InformationElement parser(final String name, final Semantics semantics, final String unit) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) throws InvalidPacketException {
                try {
                    // Not InetAddress.getByAddress: that maps a 16-byte IPv4-mapped address
                    // (::ffff:a.b.c.d) to an Inet4Address, which dual-stack exporters do send in
                    // IPv6 fields, and the cast to Inet6Address then fails. This overload keeps
                    // the declared type for every 16-byte input.
                    return new IPv6AddressValue(name, semantics, unit,
                            Inet6Address.getByAddress(null, bytes(buffer, 16), (NetworkInterface) null));
                } catch (UnknownHostException e) {
                    throw new InvalidPacketException(buffer, "Error parsing IPv6 value", e);
                }
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getMinimumFieldLength() {
                return 16;
            }

            @Override
            public int getMaximumFieldLength() {
                return 16;
            }
        };
    }

    @Override
    public Inet6Address getValue() {
        return this.value;
    }

    @Override
    public <X> X accept(ValueVisitor<X> visitor) {
        return Objects.requireNonNull(visitor).visit(this);
    }
}
