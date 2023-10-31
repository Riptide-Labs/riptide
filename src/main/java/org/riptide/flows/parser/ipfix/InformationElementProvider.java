package org.riptide.flows.parser.ipfix;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import jakarta.xml.bind.JAXB;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.ie.InformationElementDatabase;
import org.riptide.flows.parser.ie.Semantics;
import org.riptide.flows.parser.ie.values.BooleanValue;
import org.riptide.flows.parser.ie.values.DateTimeValue;
import org.riptide.flows.parser.ie.values.FloatValue;
import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;
import org.riptide.flows.parser.ie.values.ListValue;
import org.riptide.flows.parser.ie.values.MacAddressValue;
import org.riptide.flows.parser.ie.values.OctetArrayValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import static org.riptide.flows.parser.ipfix.Registry.NAMESPACE;

public class InformationElementProvider implements InformationElementDatabase.Provider {

    @VisibleForTesting
    static final String XML_FILE_LOCATION = "/ipfix-information-elements.xml";
    private static final Map<String, Semantics> SEMANTICS_LOOKUP = ImmutableMap.<String, Semantics>builder()
            .put("default", Semantics.DEFAULT)
            .put("quantity", Semantics.QUANTITY)
            .put("totalCounter", Semantics.TOTAL_COUNTER)
            .put("deltaCounter", Semantics.DELTA_COUNTER)
            .put("identifier", Semantics.IDENTIFIER)
            .put("flags", Semantics.FLAGS)
            .put("list", Semantics.LIST)
            .put("snmpCounter", Semantics.SNMP_COUNTER)
            .put("snmpGauge", Semantics.SNMP_GAUGE)
            .build();

    private static final Map<String, InformationElementDatabase.ValueParserFactory> TYPE_LOOKUP = ImmutableMap.<String, InformationElementDatabase.ValueParserFactory>builder()
            .put("octetArray", OctetArrayValue::parser)
            .put("unsigned8", UnsignedValue::parserWith8Bit)
            .put("unsigned16", UnsignedValue::parserWith16Bit)
            .put("unsigned32", UnsignedValue::parserWith32Bit)
            .put("unsigned64", UnsignedValue::parserWith64Bit)
            .put("signed8", SignedValue::parserWith8Bit)
            .put("signed16", SignedValue::parserWith16Bit)
            .put("signed32", SignedValue::parserWith32Bit)
            .put("signed64", SignedValue::parserWith64Bit)
            .put("float32", FloatValue::parserWith32Bit)
            .put("float64", FloatValue::parserWith64Bit)
            .put("boolean", BooleanValue::parser)
            .put("macAddress", MacAddressValue::parser)
            .put("string", StringValue::parser)
            .put("dateTimeSeconds", DateTimeValue::parserWithSeconds)
            .put("dateTimeMilliseconds", DateTimeValue::parserWithMilliseconds)
            .put("dateTimeMicroseconds", DateTimeValue::parserWithMicroseconds)
            .put("dateTimeNanoseconds", DateTimeValue::parserWithNanoseconds)
            .put("ipv4Address", IPv4AddressValue::parser)
            .put("ipv6Address", IPv6AddressValue::parser)
            .put("basicList", ListValue::parserWithBasicList)
            .put("subTemplateList", ListValue::parserWithSubTemplateList)
            .put("subTemplateMultiList", ListValue::parserWithSubTemplateMultiList)
            .build();

    @Override
    public void load(final InformationElementDatabase.Adder adder) {
        try (var is = getClass().getResourceAsStream(XML_FILE_LOCATION)) {
            if (is == null) {
                throw new IllegalStateException("Could not find xml file %s".formatted(XML_FILE_LOCATION));
            }
            final var registry = JAXB.unmarshal(is, Registry.class);
            final var elementRegistry = registry.getRegistryById("ipfix-information-elements"); // the elements we want to parse/read
            elementRegistry.getRecords().stream()
                    .filter(record -> record.getElementId() != null)
                    .filter(record -> TYPE_LOOKUP.containsKey(record.getDataType()))
                    .forEach(record -> {
                        final var valueParserFactory = TYPE_LOOKUP.get(record.getDataType());
                        final var semantics = SEMANTICS_LOOKUP.get(record.getDataTypeSemantics());
                        adder.add(Protocol.IPFIX, record.getElementId(), valueParserFactory, record.getName(), semantics);
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

@XmlRootElement(namespace = NAMESPACE, name = "registry")
@XmlAccessorType(XmlAccessType.NONE)
@Data
class Registry {
    
    public static final String NAMESPACE = "http://www.iana.org/assignments";
    
    @XmlAttribute(required = true)
    private String id;

    @XmlElement(namespace = NAMESPACE, required = true)
    public String title;

    @XmlElement(namespace = NAMESPACE, required = true)
    private LocalDate created;

    @XmlElement(namespace = NAMESPACE, required = true)
    private LocalDate updated;

    @XmlElement(namespace = NAMESPACE, required = true)
    private List<String> note;

    @XmlElement(namespace = NAMESPACE, required = true, name = "registry")
    private List<Registry> registries;

    @XmlElement(namespace = NAMESPACE, required = true, name = "record")
    private List<Record> records;

    @XmlElementWrapper(namespace = NAMESPACE, name = "people")
    @XmlElement(namespace = NAMESPACE, name = "person")
    private List<Person> people = new ArrayList<>();

    public Registry getRegistryById(String registryId) {
        return registries.stream()
                .filter(it -> it.getId().equals(registryId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Could not find a registry with id '%s'. Available registries are: [%s]".formatted(registryId, registries.stream().map(Registry::getId).collect(Collectors.joining(", ")))));
    }
}


@XmlRootElement(name = "registry", namespace = NAMESPACE)
@XmlAccessorType(XmlAccessType.NONE)
@Data
class Record {
    @XmlElement(namespace = NAMESPACE)
    private Integer elementId;

    @XmlElement(namespace = NAMESPACE)
    private String name;

    @XmlElement(namespace = NAMESPACE)
    private String dataType;

    @XmlElement(namespace = NAMESPACE)
    private String dataTypeSemantics;
}

@XmlRootElement(namespace = NAMESPACE)
@XmlAccessorType(XmlAccessType.NONE)
@Data
class Person {
    @XmlAttribute
    private String id;

    @XmlElement(namespace = NAMESPACE)
    private String name;

    @XmlElement(namespace = NAMESPACE)
    private String uri;

    @XmlElement(namespace = NAMESPACE)
    private String updated;
}

