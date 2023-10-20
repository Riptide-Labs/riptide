package org.riptide.flows.parser.ipfix.proto;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public class TemplateSet extends FlowSet<TemplateRecord> {
    public final List<TemplateRecord> records;

    public TemplateSet(final Packet packet,
                       final FlowSetHeader header,
                       final ByteBuf buffer) throws InvalidPacketException {
        super(packet, header);

        final List<TemplateRecord> records = new LinkedList();
        while (buffer.isReadable(TemplateRecordHeader.SIZE)) {
            final TemplateRecordHeader recordHeader = new TemplateRecordHeader(buffer);
            records.add(new TemplateRecord(recordHeader, buffer));
        }

        if (records.size() == 0) {
            throw new InvalidPacketException(buffer, "Empty set");
        }

        this.records = Collections.unmodifiableList(records);
    }

    @Override
    public Iterator<TemplateRecord> iterator() {
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
