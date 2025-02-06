package org.riptide.flows.parser.netflow5.proto;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.FlowPacket;
import org.riptide.flows.parser.netflow5.Netflow5FlowBuilder;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.riptide.flows.utils.BufferUtils.slice;

public final class Packet implements Iterable<Record>, FlowPacket {

    public final Header header;

    public final List<Record> records;

    public Packet(final Header header,
                  final ByteBuf buffer) throws InvalidPacketException {
        this.header = Objects.requireNonNull(header);

        final List<Record> records = new LinkedList<>();

        while (buffer.isReadable(Record.SIZE)
                && records.size() < this.header.count) {
            final Record record = new Record(this, slice(buffer, Record.SIZE));
            records.add(record);
        }

        if (records.size() != this.header.count) {
            throw new InvalidPacketException(buffer, "Incomplete packet");
        }

        this.records = records;
    }

    @Override
    public Iterator<Record> iterator() {
        return this.records.iterator();
    }

    @Override
    public Stream<Flow> buildFlows(final Instant receivedAt) {
        return this.records.stream()
                .map(record -> Netflow5FlowBuilder.buildFlow(receivedAt, this.header, record));
    }

    @Override
    public long getObservationDomainId() {
        return ((long) this.header.engineType) << 8L + ((long) this.header.engineId);
    }

    @Override
    public long getSequenceNumber() {
        return this.header.flowSequence;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", this.header)
                .add("records", this.records)
                .toString();
    }
}
