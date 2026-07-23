/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.netflow9.proto;

import java.util.Collections;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.session.Template;
import org.riptide.flows.parser.session.Session;

import com.google.common.base.MoreObjects;

import io.netty.buffer.ByteBuf;

public final class DataSet extends FlowSet<DataRecord> {
    private final Session.Resolver resolver;

    public final Template template;

    public final List<DataRecord> records;

    public DataSet(final Packet packet,
                   final FlowSetHeader header,
                   final Session.Resolver resolver,
                   final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException {
        super(packet, header);

        this.resolver = Objects.requireNonNull(resolver);
        this.template = this.resolver.lookupTemplate(this.header.setId);

        final int minimumRecordLength = template.stream()
                .mapToInt(f -> f.length()).sum();

        // A zero-length record would never advance the reader: isReadable(0) is always true and the
        // record consumes nothing, so the loop below would append until the heap is gone. Reject the
        // template here rather than trusting whoever installed it, which keeps the loop terminating
        // no matter how a degenerate template got into the session.
        if (minimumRecordLength <= 0) {
            throw new InvalidPacketException(buffer, "Template %d describes a zero-length record", this.header.setId);
        }

        final List<DataRecord> parsedRecords = new ArrayList<>();
        while (buffer.isReadable(minimumRecordLength)) {
            parsedRecords.add(new DataRecord(this, resolver, template, buffer));
        }

        if (parsedRecords.size() == 0) {
            throw new InvalidPacketException(buffer, "Empty set");
        }

        this.records = Collections.unmodifiableList(parsedRecords);
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
