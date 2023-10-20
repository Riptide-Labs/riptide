package org.riptide.flows.parser.netflow9.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;
import static org.riptide.flows.utils.BufferUtils.uint32;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public class Header {

    /*
     0                   1                   2                   3
     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    |       Version Number          |            Count              |
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    |                           sysUpTime                           |
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    |                           UNIX Secs                           |
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    |                       Sequence Number                         |
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    |                        Source ID                              |
    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    */

    public static final int SIZE = 20;

    public static final int VERSION = 0x0009;

    public final int versionNumber; // uint16 - must be 0x0009
    public final int count; // uint16
    public final long sysUpTime; // uint32
    public final long unixSecs; // uint32
    public final long sequenceNumber; // uint32
    public final long sourceId; // uint32

    public Header(final ByteBuf buffer) throws InvalidPacketException {
        this.versionNumber = uint16(buffer);
        if (this.versionNumber != VERSION) {
            throw new InvalidPacketException(buffer, "Invalid version number: 0x%04X", versionNumber);
        }

        this.count = uint16(buffer);
        if (this.count <= 0) {
            throw new InvalidPacketException(buffer, "Empty packet");
        }

        this.sysUpTime = uint32(buffer);
        this.unixSecs = uint32(buffer);
        this.sequenceNumber = uint32(buffer);
        this.sourceId = uint32(buffer);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("versionNumber", versionNumber)
                .add("count", count)
                .add("sysUpTime", sysUpTime)
                .add("unixSecs", unixSecs)
                .add("sequenceNumber", sequenceNumber)
                .add("sourceId", sourceId)
                .toString();
    }
}
