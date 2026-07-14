/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow9.proto;

import java.util.Collections;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;

import org.riptide.flows.parser.exceptions.InvalidPacketException;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class OptionsTemplateSet extends FlowSet<OptionsTemplateRecord> {

    public final List<OptionsTemplateRecord> records;

    public OptionsTemplateSet(final Packet packet,
                              final FlowSetHeader header,
                              final ByteBuf buffer) throws InvalidPacketException {
        super(packet, header);

        final List<OptionsTemplateRecord> parsedRecords = new ArrayList<>();
        while (buffer.isReadable(OptionsTemplateRecordHeader.SIZE)) {
            final OptionsTemplateRecordHeader recordHeader = new OptionsTemplateRecordHeader(buffer);
            parsedRecords.add(new OptionsTemplateRecord(this, recordHeader, buffer));
        }

        if (parsedRecords.isEmpty()) {
            throw new InvalidPacketException(buffer, "Empty set");
        }

        this.records = Collections.unmodifiableList(parsedRecords);
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
