package org.riptide.flows.parser.netflow5.proto;

import static org.riptide.flows.utils.BufferUtils.slice;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.Streams;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.ie.RecordProvider;
import org.riptide.flows.parser.ie.Value;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;

import io.netty.buffer.ByteBuf;

public final class Packet implements Iterable<Record>, RecordProvider {

    public final Header header;

    public final List<Record> records;

    public Packet(final Header header,
                  final ByteBuf buffer) throws InvalidPacketException {
        this.header = Objects.requireNonNull(header);

        final List<Record> records = new LinkedList();

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
    public Stream<Map<String, Value<?>>> getRecords() {
        final Stream<Value<?>> header = this.header.asValues();

        return this.records.stream()
                .map(Record::asValues)
                .map(record -> Streams.concat(header, record)
                        .collect(Collectors.toUnmodifiableMap(Value::getName, Function.identity())));
    }

    @Override
    public long getObservationDomainId() {
        return this.header.engineType << 8 + this.header.engineId;
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
