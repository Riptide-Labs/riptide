package org.riptide.flows.parser.ipfix.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;
import static org.riptide.flows.utils.BufferUtils.uint32;

import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.MissingTemplateException;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.InformationElementDatabase;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UndeclaredValue;
import org.riptide.flows.parser.session.Field;
import org.riptide.flows.parser.session.Scope;
import org.riptide.flows.parser.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class FieldSpecifier implements Field, Scope {
    private static final Logger LOG = LoggerFactory.getLogger(FieldSpecifier.class);

    /*
     0                   1                   2                   3
     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |E|  Information Element ident. |        Field Length           |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |                      Enterprise Number                        |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public final int informationElementId; // uint16 { enterprise_bit:1, element_id: 15 }
    public final int fieldLength; // uint16

    public final Long enterpriseNumber; // uint32

    public final InformationElement informationElement;

    public FieldSpecifier(final ByteBuf buffer) throws InvalidPacketException {
        final int elementId = uint16(buffer);

        this.informationElementId = elementId & 0x7FFF;
        this.fieldLength = uint16(buffer);

        if ((elementId & 0x8000) == 0) {
            this.enterpriseNumber = null;
        } else {
            this.enterpriseNumber = uint32(buffer);
        }

        this.informationElement = InformationElementDatabase.instance
                .lookup(Protocol.IPFIX, this.enterpriseNumber, this.informationElementId).orElseGet(() -> {
                    LOG.warn("Undeclared information element: {}", UndeclaredValue.nameFor(this.enterpriseNumber, this.informationElementId));
                    return UndeclaredValue.parser(this.enterpriseNumber, this.informationElementId);
                });

        if (this.fieldLength > this.informationElement.getMaximumFieldLength() || this.fieldLength < this.informationElement.getMinimumFieldLength()) {
            throw new InvalidPacketException(buffer, "Template field '%s' has illegal size: %d (min=%d, max=%d)",
                    this.informationElement.getName(),
                    this.fieldLength,
                    this.informationElement.getMinimumFieldLength(),
                    this.informationElement.getMaximumFieldLength());
        }
    }

    @Override
    public Value<?> parse(final Session.Resolver resolver, final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
        return this.informationElement.parse(resolver, buffer);
    }

    @Override
    public int length() {
        return this.fieldLength;
    }

    @Override
    public String getName() {
        return this.informationElement.getName();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("informationElementId", informationElementId)
                .add("enterpriseNumber", enterpriseNumber)
                .add("fieldLength", fieldLength)
                .toString();
    }
}
