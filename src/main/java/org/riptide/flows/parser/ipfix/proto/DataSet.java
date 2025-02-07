package org.riptide.flows.parser.ipfix.proto;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.session.Template;
import org.riptide.flows.parser.session.Session;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public class DataSet extends FlowSet<DataRecord> {

    public final Template template;

    public final List<DataRecord> records;

    public DataSet(final Packet packet,
                   final FlowSetHeader header,
                   final Session.Resolver resolver,
                   final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
        super(packet, header);

        Session.Resolver resolver1 = Objects.requireNonNull(resolver);
        this.template = resolver1.lookupTemplate(this.header.setId);

        // For variable length fields we assume at least the length value (1 byte) to be present
        final int minimumRecordLength = this.template.stream()
                .mapToInt(f -> f.length() != DataRecord.VARIABLE_SIZED ? f.length() : 1).sum();

        final List<DataRecord> records = new LinkedList<>();
        while (buffer.isReadable(minimumRecordLength)) {
            records.add(new DataRecord(this, resolver1, this.template, buffer));
        }

        if (records.isEmpty()) {
            throw new InvalidPacketException(buffer, "Empty set");
        }

        this.records = Collections.unmodifiableList(records);
    }

    @Override
    public Iterator<DataRecord> iterator() {
        return this.records.iterator();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", header)
                .add("records", records)
                .toString();
    }
}
