package org.riptide.flows.parser.netflow5.proto;

import static org.riptide.flows.utils.BufferUtils.uint16;
import static org.riptide.flows.utils.BufferUtils.uint32;
import static org.riptide.flows.utils.BufferUtils.uint8;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

import java.util.stream.Stream;

public final class Header {

    public static final int SIZE = 24;

    public static final int VERSION = 0x0005;

    public final int versionNumber; // uint16 - must be 0x0005
    public final int count; // uint16
    public final long sysUptime; // uint32
    public final long unixSecs; // uint32
    public final long unixNSecs; // uint32
    public final long flowSequence; // uint32
    public final int engineType; // uint8
    public final int engineId; // uint8
    public final int samplingAlgorithm;
    public final int samplingInterval;

    public Header(final ByteBuf buffer) throws InvalidPacketException {
        this.versionNumber = uint16(buffer);
        if (this.versionNumber != VERSION) {
            throw new InvalidPacketException(buffer, "Invalid version number: 0x%04X", this.versionNumber);
        }

        this.count = uint16(buffer);
        if (this.count < 1 || this.count > 30) {
            throw new InvalidPacketException(buffer, "Invalid record count: %d", this.count);
        }

        this.sysUptime = uint32(buffer);
        this.unixSecs = uint32(buffer);
        this.unixNSecs = uint32(buffer);
        this.flowSequence = uint32(buffer);
        this.engineType = uint8(buffer);
        this.engineId = uint8(buffer);

        final int sampling = uint16(buffer);
        this.samplingAlgorithm = sampling >>> 14;
        this.samplingInterval = sampling & ((2 << 13) - 1);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("versionNumber", this.versionNumber)
                .add("count", this.count)
                .add("sysUptime", this.sysUptime)
                .add("unixSecs", this.unixSecs)
                .add("unixNSecs", this.unixNSecs)
                .add("flowSequence", this.flowSequence)
                .add("engineType", this.engineType)
                .add("engineId", this.engineId)
                .add("samplingAlgorithm", this.samplingAlgorithm)
                .add("samplingInterval", this.samplingInterval)
                .toString();
    }

    public Stream<Value<?>> asValues() {
        return Stream.of(
                new UnsignedValue("@versionNumber", this.versionNumber),
                new UnsignedValue("@count", this.count),
                new UnsignedValue("@sysUptime", this.sysUptime),
                new UnsignedValue("@unixSecs", this.unixSecs),
                new UnsignedValue("@unixNSecs", this.unixNSecs),
                new UnsignedValue("@flowSequence", this.flowSequence),
                new UnsignedValue("@engineType", this.engineType),
                new UnsignedValue("@engineId", this.engineId),
                new UnsignedValue("@samplingAlgorithm", this.samplingAlgorithm),
                new UnsignedValue("@samplingInterval", this.samplingInterval)
        );
    }
}
