package org.riptide.flows.parser.ipfix.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class OptionsTemplateRecordHeader {

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |         Template ID (> 255)   |         Field Count           |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |      Scope Field Count        |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int SIZE = 6;

    public final int templateId; // uint16
    public final int fieldCount; // uint16
    public final int scopeFieldCount; // uint16

    public OptionsTemplateRecordHeader(final ByteBuf buffer) throws InvalidPacketException {
        this.templateId = uint16(buffer);
        if (this.templateId <= 255 && this.templateId != FlowSetHeader.OPTIONS_TEMPLATE_SET_ID) {
            // Since Template IDs are used as Set IDs in the Sets they describe
            throw new InvalidPacketException(buffer, "Invalid template ID: %d", this.templateId);
        }

        this.fieldCount = uint16(buffer);

        this.scopeFieldCount = uint16(buffer);
        if (this.scopeFieldCount > this.fieldCount) {
            throw new InvalidPacketException(buffer, "More scope fields than fields available: %d > %d", this.scopeFieldCount, this.fieldCount);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("templateId", templateId)
                .add("fieldCount", fieldCount)
                .add("scopeFieldCount", scopeFieldCount)
                .toString();
    }
}
