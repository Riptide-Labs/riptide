package org.riptide.flows.parser.ipfix.proto;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.exceptions.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class OptionsTemplateRecord implements Record {

    public final OptionsTemplateRecordHeader header;

    public final List<FieldSpecifier> scopes;
    public final List<FieldSpecifier> fields;

    public OptionsTemplateRecord(final OptionsTemplateRecordHeader header,
                                 final ByteBuf buffer) throws InvalidPacketException {
        this.header = Objects.requireNonNull(header);

        final List<FieldSpecifier> scopes = new LinkedList<>();
        for (int i = 0; i < this.header.scopeFieldCount; i++) {
            final FieldSpecifier scopeField = new FieldSpecifier(buffer);

            scopes.add(scopeField);
        }

        final List<FieldSpecifier> fields = new LinkedList<>();
        for (int i = this.header.scopeFieldCount; i < this.header.fieldCount; i++) {
            final FieldSpecifier field = new FieldSpecifier(buffer);

            fields.add(field);
        }

        this.scopes = Collections.unmodifiableList(scopes);
        this.fields = Collections.unmodifiableList(fields);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", header)
                .add("scopes", scopes)
                .add("fields", fields)
                .toString();
    }
}
