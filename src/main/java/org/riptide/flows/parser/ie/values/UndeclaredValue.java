package org.riptide.flows.parser.ie.values;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.session.Session;

import java.util.Objects;

import static org.riptide.flows.utils.BufferUtils.bytes;

public class UndeclaredValue extends Value<byte[]> {
    public final byte[] value;

    public UndeclaredValue(final Long enterpriseNumber,
                           final int informationElementId,
                           final byte[] value) {
        super(nameFor(enterpriseNumber, informationElementId), null);
        this.value = Objects.requireNonNull(value);
    }

    public UndeclaredValue(final int informationElementId,
                           final byte[] value) {
        this(null, informationElementId, value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", getName())
                .add("data", value)
                .toString();
    }

    @Override
    public byte[] getValue() {
        return this.value;
    }

    public static InformationElement parser(final int informationElementId) {
        return parser(null, informationElementId);
    }

    public static InformationElement parser(final Long enterpriseNumber,
                                            final int informationElementId) {
        return new InformationElement() {
            @Override
            public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) {
                return new UndeclaredValue(enterpriseNumber, informationElementId, bytes(buffer, buffer.readableBytes()));
            }

            @Override
            public String getName() {
                return nameFor(enterpriseNumber, informationElementId);
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

    public static String nameFor(final Long enterpriseNumber,
                                 final int informationElementId) {
        final var s = new StringBuilder();
        
        if (enterpriseNumber != null) {
            s.append(enterpriseNumber);
            s.append(':');
        }
        
        s.append(informationElementId);
        
        return s.toString();
    }
}
