package org.riptide.flows.parser.netflow9.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class OptionsTemplateRecordHeader {

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |         Template ID           |      Option Scope Length      |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |        Option Length          |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int SIZE = 6;

    public final int templateId; // uint16
    public final int optionScopeLength; // uint16
    public final int optionLength; // uint16

    public OptionsTemplateRecordHeader(final ByteBuf buffer) throws InvalidPacketException {
        this.templateId = uint16(buffer);
        if (this.templateId <= 255) {
            // Since Template IDs are used as Set IDs in the Sets they describe
            throw new InvalidPacketException(buffer, "Invalid template ID: %d", this.templateId);
        }

        this.optionScopeLength = uint16(buffer);
        this.optionLength = uint16(buffer);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("templateId", templateId)
                .add("optionScopeLength", optionScopeLength)
                .add("optionLength", optionLength)
                .toString();
    }
}
