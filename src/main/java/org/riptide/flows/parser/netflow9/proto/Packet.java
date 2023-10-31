package org.riptide.flows.parser.netflow9.proto;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;
import io.netty.buffer.ByteBuf;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.MissingTemplateException;
import org.riptide.flows.parser.ie.RecordProvider;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.riptide.flows.utils.BufferUtils.slice;

public final class Packet implements Iterable<FlowSet<?>>, RecordProvider {
    private static final Logger LOG = LoggerFactory.getLogger(Packet.class);

    /*
     +--------+-------------------------------------------+
     |        | +----------+ +---------+ +----------+     |
     | Packet | | Template | | Data    | | Options  |     |
     | Header | | FlowSet  | | FlowSet | | Template | ... |
     |        | |          | |         | | FlowSet  |     |
     |        | +----------+ +---------+ +----------+     |
     +--------+-------------------------------------------+
    */

    public final Header header;

    public final List<TemplateSet> templateSets;
    public final List<OptionsTemplateSet> optionTemplateSets;
    public final List<DataSet> dataSets;

    public Packet(final Session session,
                  final Header header,
                  final ByteBuf buffer) throws InvalidPacketException {
        this.header = Objects.requireNonNull(header);

        final List<TemplateSet> templateSets = new LinkedList<>();
        final List<OptionsTemplateSet> optionTemplateSets = new LinkedList<>();
        final List<DataSet> dataSets = new LinkedList<>();
        while (buffer.isReadable()) {
            // We ignore header.counter here, because different exporters interpret it as flowset count or record count

            final ByteBuf headerBuffer = slice(buffer, FlowSetHeader.SIZE);
            final FlowSetHeader setHeader = new FlowSetHeader(headerBuffer);

            final ByteBuf payloadBuffer = slice(buffer, setHeader.length - FlowSetHeader.SIZE);
            switch (setHeader.getType()) {
                case TEMPLATE_FLOWSET: {
                    final TemplateSet templateSet = new TemplateSet(this, setHeader, payloadBuffer);

                    for (final TemplateRecord record : templateSet) {
                        if (record.header.fieldCount == 0) {
                            // Empty template means revocation
                            if (record.header.templateId == FlowSetHeader.TEMPLATE_SET_ID) {
                                // Remove all templates
                                session.removeAllTemplate(this.header.sourceId, Template.Type.TEMPLATE);

                            } else {
                                // Empty template means revocation
                                session.removeTemplate(this.header.sourceId, record.header.templateId);
                            }

                        } else {
                            session.addTemplate(this.header.sourceId,
                                    Template.builder(record.header.templateId, Template.Type.TEMPLATE)
                                            .withFields(record.fields)
                                            .build());
                        }
                    }

                    templateSets.add(templateSet);
                    break;
                }

                case OPTIONS_TEMPLATE_FLOWSET: {
                    final OptionsTemplateSet optionsTemplateSet = new OptionsTemplateSet(this, setHeader, payloadBuffer);

                    for (final OptionsTemplateRecord record : optionsTemplateSet) {
                        session.addTemplate(this.header.sourceId,
                                Template.builder(record.header.templateId, Template.Type.OPTIONS_TEMPLATE)
                                        .withScopes(record.scopes)
                                        .withFields(record.fields)
                                        .build());
                    }

                    optionTemplateSets.add(optionsTemplateSet);
                    break;
                }

                case DATA_FLOWSET: {
                    final Session.Resolver resolver = session.getResolver(header.sourceId);

                    final DataSet dataSet;
                    try {
                        dataSet = new DataSet(this, setHeader, resolver, payloadBuffer);
                    } catch (final MissingTemplateException ex) {
                        LOG.debug("Skipping data-set due to missing template: {}", ex.getMessage());
                        break;
                    }

                    if (dataSet.template.type == Template.Type.OPTIONS_TEMPLATE) {
                        for (final DataRecord record : dataSet) {
                            session.addOptions(this.header.sourceId, dataSet.template.id, record.scopes, record.fields);
                        }
                    } else {
                        dataSets.add(dataSet);
                    }

                    break;
                }

                case null, default: {
                    throw new InvalidPacketException(buffer, "Invalid Set ID: %d", setHeader.setId);
                }
            }
        }

        this.templateSets = Collections.unmodifiableList(templateSets);
        this.optionTemplateSets = Collections.unmodifiableList(optionTemplateSets);
        this.dataSets = Collections.unmodifiableList(dataSets);
    }

    @Override
    public Iterator<FlowSet<?>> iterator() {
        return Iterators.concat(this.templateSets.iterator(),
                this.optionTemplateSets.iterator(),
                this.dataSets.iterator());
    }

    @Override
    public Stream<Map<String, Value<?>>> getRecords() {
        final int recordCount = this.dataSets.stream()
                .mapToInt(s -> s.records.size())
                .sum();

        return this.dataSets.stream()
                .flatMap(s -> s.records.stream())
                .map(r -> Streams.concat(
                        Stream.of(
                                new UnsignedValue("@recordCount", recordCount),
                                new UnsignedValue("@sequenceNumber", this.header.sequenceNumber),
                                new UnsignedValue("@sysUpTime", this.header.sysUpTime),
                                new UnsignedValue("@unixSecs", this.header.unixSecs),
                                new UnsignedValue("@sourceId", this.header.sourceId)),
                        r.fields.stream(),
                        r.options.stream()
                ).collect(Collectors.toUnmodifiableMap(Value::getName, Function.identity())));
    }

    @Override
    public long getObservationDomainId() {
        return this.header.sourceId;
    }

    @Override
    public long getSequenceNumber() {
        return this.header.sequenceNumber;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", header)
                .add("templateSets", this.templateSets)
                .add("optionTemplateSets", this.optionTemplateSets)
                .add("dataTemplateSets", this.dataSets)
                .toString();
    }
}
