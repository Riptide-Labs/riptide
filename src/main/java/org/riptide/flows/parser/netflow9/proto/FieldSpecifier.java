package org.riptide.flows.parser.netflow9.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.InformationElementDatabase;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UndeclaredValue;
import org.riptide.flows.parser.session.Field;
import org.riptide.flows.parser.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class FieldSpecifier implements Field {
    private static final Logger LOG = LoggerFactory.getLogger(FieldSpecifier.class);

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |        Field Type N           |         Field Length N        |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int SIZE = 4;

    public final int fieldType; // uint16
    public final int fieldLength; // uint16

    public final InformationElement informationElement;

    public FieldSpecifier(final ByteBuf buffer) throws InvalidPacketException {
        this.fieldType = uint16(buffer);
        this.fieldLength = uint16(buffer);

        this.informationElement = InformationElementDatabase.instance
                .lookup(Protocol.NETFLOW9, this.fieldType).orElseGet(() -> {
                    LOG.warn("Undeclared field type: {}", UndeclaredValue.nameFor(null, this.fieldType));
                    return UndeclaredValue.parser(this.fieldType);
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
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("fieldType", fieldType)
                .add("fieldLength", fieldLength)
                .toString();
    }
}
