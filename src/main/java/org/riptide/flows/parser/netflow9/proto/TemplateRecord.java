package org.riptide.flows.parser.netflow9.proto;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class TemplateRecord implements Record {

    public final TemplateSet set;  // Enclosing set

    public final TemplateRecordHeader header;

    public final List<FieldSpecifier> fields;

    public TemplateRecord(final TemplateSet set,
                          final TemplateRecordHeader header,
                          final ByteBuf buffer) throws InvalidPacketException {
        this.set = Objects.requireNonNull(set);

        this.header = Objects.requireNonNull(header);

        final List<FieldSpecifier> fields = new LinkedList<>();
        for (int i = 0; i < this.header.fieldCount; i++) {
            final FieldSpecifier field = new FieldSpecifier(buffer);
            fields.add(field);
        }

        this.fields = Collections.unmodifiableList(fields);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", header)
                .add("fields", fields)
                .toString();
    }
}
