package org.riptide.flows.parser.netflow9.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class FlowSetHeader {

    /*
     0                   1                   2                   3
     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    |       FlowSet ID = 1          |          Length               |
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int TEMPLATE_SET_ID = 0;
    public static final int OPTIONS_TEMPLATE_SET_ID = 1;

    public enum Type {
        TEMPLATE_FLOWSET,
        OPTIONS_TEMPLATE_FLOWSET,
        DATA_FLOWSET
    }

    public static final int SIZE = 4;

    public final int setId; // uint16
    public final int length; // uint16

    public FlowSetHeader(final ByteBuf buffer) throws InvalidPacketException {
        this.setId = uint16(buffer);
        if (this.setId < 256 && this.setId != TEMPLATE_SET_ID && this.setId != OPTIONS_TEMPLATE_SET_ID) {
            throw new InvalidPacketException(buffer, "Invalid set ID: %d", this.setId);
        }

        this.length = uint16(buffer);
    }

    public Type getType() {
        if (this.setId == TEMPLATE_SET_ID) return Type.TEMPLATE_FLOWSET;
        if (this.setId == OPTIONS_TEMPLATE_SET_ID) return Type.OPTIONS_TEMPLATE_FLOWSET;
        if (this.setId >= 256) return Type.DATA_FLOWSET;

        return null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("flowSetId", setId)
                .add("length", length)
                .toString();
    }
}
