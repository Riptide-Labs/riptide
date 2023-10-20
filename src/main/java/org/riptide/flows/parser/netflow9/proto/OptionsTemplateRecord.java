package org.riptide.flows.parser.netflow9.proto;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class OptionsTemplateRecord implements Record {

    public final OptionsTemplateSet set;  // Enclosing set

    public final OptionsTemplateRecordHeader header;

    public final List<ScopeFieldSpecifier> scopes;
    public final List<FieldSpecifier> fields;

    public OptionsTemplateRecord(final OptionsTemplateSet set,
                                 final OptionsTemplateRecordHeader header,
                                 final ByteBuf buffer) throws InvalidPacketException {
        this.set = Objects.requireNonNull(set);

        this.header = Objects.requireNonNull(header);

        final List<ScopeFieldSpecifier> scopeFields = new LinkedList<>();
        for (int i = 0; i < this.header.optionScopeLength; i += ScopeFieldSpecifier.SIZE) {
            final ScopeFieldSpecifier scopeField = new ScopeFieldSpecifier(buffer);

            // Ignore scope fields without a value so they will always match during scope resolution
            if (scopeField.fieldLength == 0) {
                continue;
            }

            scopeFields.add(scopeField);
        }

        final List<FieldSpecifier> fields = new LinkedList<>();
        for (int i = 0; i < this.header.optionLength; i += FieldSpecifier.SIZE) {
            final FieldSpecifier field = new FieldSpecifier(buffer);
            fields.add(field);
        }

        this.scopes = Collections.unmodifiableList(scopeFields);
        this.fields = Collections.unmodifiableList(fields);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", header)
                .add("scopeFields", scopes)
                .add("fields", fields)
                .toString();
    }
}
