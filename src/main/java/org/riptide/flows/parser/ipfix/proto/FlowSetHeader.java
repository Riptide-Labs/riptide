package org.riptide.flows.parser.ipfix.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;

import org.riptide.flows.parser.exceptions.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class FlowSetHeader {

    /*
      0                   1                   2                   3
      0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     |          Set ID               |          Length               |
     +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int TEMPLATE_SET_ID = 2;
    public static final int OPTIONS_TEMPLATE_SET_ID = 3;

    public enum Type {
        TEMPLATE_SET,
        OPTIONS_TEMPLATE_SET,
        DATA_SET
    }

    public static final int SIZE = 4;

    public final int setId; // uint16
    public final int length; // uint16

    public final Type type;

    public FlowSetHeader(final ByteBuf buffer) throws InvalidPacketException {
        this.setId = uint16(buffer);
        if (this.setId >= 256) {
            this.type = Type.DATA_SET;
        } else if (this.setId == TEMPLATE_SET_ID) {
            this.type = Type.TEMPLATE_SET;
        } else if (this.setId == OPTIONS_TEMPLATE_SET_ID) {
            this.type = Type.OPTIONS_TEMPLATE_SET;
        } else {
            // The Set ID values of 0 and 1 are not used, for historical reasons [RFC3954], values from 4 to 255 are
            // reserved for future use.
            throw new InvalidPacketException(buffer, "Invalid set ID: %d", this.setId);
        }

        this.length = uint16(buffer);
    }

    public Type getType() {
        return this.type;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("setId", setId)
                .add("length", length)
                .toString();
    }
}
