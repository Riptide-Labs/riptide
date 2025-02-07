package org.riptide.flows.parser.netflow9.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;

import java.util.List;
import java.util.Optional;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.session.Field;
import org.riptide.flows.parser.session.Scope;
import org.riptide.flows.parser.session.Session;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import io.netty.buffer.ByteBuf;

public final class ScopeFieldSpecifier implements Field, Scope {

    public static final String SCOPE_SYSTEM = "SCOPE:SYSTEM";
    public static final String SCOPE_INTERFACE = "SCOPE:INTERFACE";
    public static final String SCOPE_LINE_CARD = "SCOPE:LINE_CARD";
    public static final String SCOPE_CACHE = "SCOPE:CACHE";
    public static final String SCOPE_TEMPLATE = "SCOPE:TEMPLATE";

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |     Scope Field Type N        |      Scope Field Length N     |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int SIZE = 4;

    public final int fieldType; // uint16
    public final int fieldLength; // uint16

    public final InformationElement field;

    public ScopeFieldSpecifier(final ByteBuf buffer) throws InvalidPacketException {
        this.fieldType = uint16(buffer);
        this.fieldLength = uint16(buffer);

        this.field = from(this.fieldType)
                .orElseThrow(() -> new InvalidPacketException(buffer, "Invalid scope field type: 0x%04X", this.fieldType));

        if (this.fieldLength > this.field.getMaximumFieldLength() || this.fieldLength < this.field.getMinimumFieldLength()) {
            throw new InvalidPacketException(buffer, "Template scope field '%s' has illegal size: %d (min=%d, max=%d)",
                    this.field.getName(),
                    this.fieldLength,
                    this.field.getMinimumFieldLength(),
                    this.field.getMaximumFieldLength());
        }
    }

    @Override
    public Value<?> parse(Session.Resolver resolver, ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
        return this.field.parse(resolver, buffer);
    }

    @Override
    public int length() {
        return this.fieldLength;
    }

    @Override
    public String getName() {
        return this.field.getName();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("scopeFieldType", this.fieldType)
                .add("scopeFieldLength", this.fieldLength)
                .toString();
    }

    private static Optional<InformationElement> from(final int fieldType) {
        return switch (fieldType) {
            case 0x0001 -> Optional.of(UnsignedValue.parserWith64Bit(SCOPE_SYSTEM, null, null));
            case 0x0002 -> Optional.of(UnsignedValue.parserWith64Bit(SCOPE_INTERFACE, null, null));
            case 0x0003 -> Optional.of(UnsignedValue.parserWith64Bit(SCOPE_LINE_CARD, null, null));
            case 0x0004 -> Optional.of(UnsignedValue.parserWith64Bit(SCOPE_CACHE, null, null));
            case 0x0005 -> Optional.of(UnsignedValue.parserWith64Bit(SCOPE_TEMPLATE, null, null));
            default -> Optional.empty();
        };
    }

    public static List<Value<?>> buildScopeValues(final DataRecord record) {
        final ImmutableList.Builder<Value<?>> values = ImmutableList.builder();

        values.add(new UnsignedValue(ScopeFieldSpecifier.SCOPE_SYSTEM, record.set.packet.header.sourceId));
        values.add(new UnsignedValue(ScopeFieldSpecifier.SCOPE_TEMPLATE, record.set.template.id));

        return values.build();
    }
}
