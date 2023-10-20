package org.riptide.flows.parser.netflow9.proto;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class OptionsTemplateSet extends FlowSet<OptionsTemplateRecord> {

    public final List<OptionsTemplateRecord> records;

    public OptionsTemplateSet(final Packet packet,
                              final FlowSetHeader header,
                              final ByteBuf buffer) throws InvalidPacketException {
        super(packet, header);

        final List<OptionsTemplateRecord> records = new LinkedList<>();
        while (buffer.isReadable(OptionsTemplateRecordHeader.SIZE)) {
            final OptionsTemplateRecordHeader recordHeader = new OptionsTemplateRecordHeader(buffer);
            records.add(new OptionsTemplateRecord(this, recordHeader, buffer));
        }

        if (records.isEmpty()) {
            throw new InvalidPacketException(buffer, "Empty set");
        }

        this.records = Collections.unmodifiableList(records);
    }

    @Override
    public Iterator<OptionsTemplateRecord> iterator() {
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
