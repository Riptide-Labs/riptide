/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ipfix.proto;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.exceptions.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class TemplateRecord implements Record {

    public final TemplateRecordHeader header;

    public final List<FieldSpecifier> fields;

    public TemplateRecord(final TemplateRecordHeader header,
                          final ByteBuf buffer) throws InvalidPacketException {
        this.header = Objects.requireNonNull(header);

        final List<FieldSpecifier> parsedFields = new ArrayList<>();
        for (int i = 0; i < this.header.fieldCount; i++) {
            final FieldSpecifier field = new FieldSpecifier(buffer);
            parsedFields.add(field);
        }

        this.fields = Collections.unmodifiableList(parsedFields);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", header)
                .add("fields", fields)
                .toString();
    }
}
