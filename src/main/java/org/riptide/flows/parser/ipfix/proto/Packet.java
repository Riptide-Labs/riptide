package org.riptide.flows.parser.ipfix.proto;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.Template;
import org.riptide.flows.visitor.BooleanVisitor;
import org.riptide.flows.visitor.DoubleVisitor;
import org.riptide.flows.visitor.DurationVisitor;
import org.riptide.flows.visitor.InetAddressVisitor;
import org.riptide.flows.visitor.InstantVisitor;
import org.riptide.flows.visitor.IntegerVisitor;
import org.riptide.flows.visitor.LongVisitor;
import org.riptide.flows.visitor.StringVisitor;
import org.riptide.flows.visitor.ValueVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.time.Instant;
import java.time.Duration;
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

@Slf4j
public final class Packet implements Iterable<FlowSet<?>> {
    private static final Logger LOG = LoggerFactory.getLogger(Packet.class);

    /*
     +----------------------------------------------------+
     | Message Header                                     |
     +----------------------------------------------------+
     | Set                                                |
     +----------------------------------------------------+
     | Set                                                |
     +----------------------------------------------------+
      ...
     +----------------------------------------------------+
     | Set                                                |
     +----------------------------------------------------+
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
            final ByteBuf headerBuffer = slice(buffer, FlowSetHeader.SIZE);
            final FlowSetHeader setHeader = new FlowSetHeader(headerBuffer);

            final ByteBuf payloadBuffer = slice(buffer, setHeader.length - FlowSetHeader.SIZE);
            switch (setHeader.getType()) {
                case TEMPLATE_SET: {
                    final TemplateSet templateSet = new TemplateSet(this, setHeader, payloadBuffer);

                    for (final TemplateRecord record : templateSet) {
                        if (record.header.fieldCount == 0) {
                            // Empty template means revocation
                            if (record.header.templateId == FlowSetHeader.TEMPLATE_SET_ID) {
                                // Remove all templates
                                session.removeAllTemplate(this.header.observationDomainId, Template.Type.TEMPLATE);

                            } else {
                                // Empty template means revocation
                                session.removeTemplate(this.header.observationDomainId, record.header.templateId);
                            }

                        } else {
                            session.addTemplate(this.header.observationDomainId,
                                    Template.builder(record.header.templateId, Template.Type.TEMPLATE)
                                            .withFields(record.fields)
                                            .build());
                        }
                    }

                    templateSets.add(templateSet);
                    break;
                }

                case OPTIONS_TEMPLATE_SET: {
                    final OptionsTemplateSet optionsTemplateSet = new OptionsTemplateSet(this, setHeader, payloadBuffer);

                    for (final OptionsTemplateRecord record : optionsTemplateSet) {
                        if (record.header.fieldCount == 0) {
                            // Empty template means revocation
                            if (record.header.templateId == FlowSetHeader.OPTIONS_TEMPLATE_SET_ID) {
                                // Remove all templates
                                session.removeAllTemplate(this.header.observationDomainId, Template.Type.OPTIONS_TEMPLATE);

                            } else {
                                // Empty template means revocation
                                session.removeTemplate(this.header.observationDomainId, record.header.templateId);
                            }

                        } else {
                            session.addTemplate(this.header.observationDomainId,
                                    Template.builder(record.header.templateId, Template.Type.OPTIONS_TEMPLATE)
                                            .withScopes(record.scopes)
                                            .withFields(record.fields)
                                            .build());
                        }
                    }

                    optionTemplateSets.add(optionsTemplateSet);
                    break;
                }

                case DATA_SET: {
                    final Session.Resolver resolver = session.getResolver(header.observationDomainId);

                    final DataSet dataSet;
                    try {
                        dataSet = new DataSet(this, setHeader, resolver, payloadBuffer);
                    } catch (final MissingTemplateException ex) {
                        LOG.debug("Skipping data-set due to missing template: {}", ex.getMessage());
                        break;
                    }

                    if (dataSet.template.type == Template.Type.OPTIONS_TEMPLATE) {
                        for (final DataRecord record : dataSet) {
                            session.addOptions(this.header.observationDomainId, dataSet.template.id, record.scopes, record.fields);
                        }
                    } else {
                        dataSets.add(dataSet);
                    }

                    break;
                }

                default: {
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

    public Stream<IpfixRawFlow> getDummyFlows() {
        final Map<Class<?>, ValueVisitor<?>> visitors = Map.of(
                Boolean.class, new BooleanVisitor(),
                Double.class, new DoubleVisitor(),
                InetAddress.class, new InetAddressVisitor(),
                Instant.class, new InstantVisitor(),
                Duration.class, new DurationVisitor(),
                Integer.class, new IntegerVisitor(),
                Long.class, new LongVisitor(),
                String.class, new StringVisitor());
        final var fieldSet = Stream.of(IpfixRawFlow.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());
        return dataSets.stream().flatMap(ds -> ds.records.stream().map(record -> {
            final var dummyFlow = new IpfixRawFlow();
            for (var value : record.getValues()) {
                try {
                    final var key = value.getName();
                    if (fieldSet.contains(key)) {
                        final var field = IpfixRawFlow.class.getDeclaredField(key);
                        final var converterVisitor = visitors.get(field.getType());
                        final var convertedValue = value.accept(converterVisitor);
                        field.setAccessible(true);
                        if (convertedValue != null) {
                            field.set(dummyFlow, convertedValue);
                        }
                    }
                } catch (Exception ex) {
                    log.error("🤡🦄💩: {}", ex.getMessage(), ex);
                }
            }
            dummyFlow.sequenceNumber = this.header.sequenceNumber;
            dummyFlow.exportTime = header.exportTime;
            dummyFlow.observationDomainId = header.observationDomainId;
            return dummyFlow;
        }));
    }

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
                                new UnsignedValue("@exportTime", this.header.exportTime),
                                new UnsignedValue("@observationDomainId", this.header.observationDomainId)),
                        r.fields.stream(),
                        r.options.stream()
                ).collect(Collectors.toUnmodifiableMap(Value::getName, Function.identity())));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("header", this.header)
                .add("templateSets", this.templateSets)
                .add("optionTemplateSets", this.optionTemplateSets)
                .add("dataTemplateSets", this.dataSets)
                .toString();
    }
}
