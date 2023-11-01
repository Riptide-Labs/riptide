package org.riptide.flows.parser.ipfix;

import jakarta.xml.bind.JAXB;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.Protocol;

import java.time.LocalDate;
import java.util.stream.Stream;

class InformationElementProviderTest {

    @Test
    void verifyFileExists() {
        Assertions.assertThat(getClass().getResourceAsStream(InformationElementProvider.XML_FILE_LOCATION)).isNotNull();
    }


    @Test
    void verifyBasicContentOfFile() {
        final var registry = JAXB.unmarshal(getClass().getResourceAsStream(InformationElementProvider.XML_FILE_LOCATION), Registry.class);
        Assertions.assertThat(registry).isNotNull();
        Assertions.assertThat(registry.getId()).isEqualTo(Protocol.IPFIX.description.toLowerCase());
        Assertions.assertThat(registry.getCreated()).isEqualTo(LocalDate.of(2007, 5, 10));
        Assertions.assertThat(registry.getUpdated()).isNotNull();
        Assertions.assertThat(registry.getPeople())
                .isNotEmpty()
                .allSatisfy(people -> {
                    Assertions.assertThat(people.getId()).isNotNull();
                    Assertions.assertThat(people.getUri()).isNotNull();
                    Assertions.assertThat(people.getUpdated()).isNotNull();
                    Assertions.assertThat(people.getName()).isNotNull();
                });
        Assertions.assertThat(registry.getNote()).allSatisfy(note -> Assertions.assertThat(note).isNotNull());

        Assertions.assertThat(registry.getRegistries()).isNotEmpty();
        Assertions.assertThat(Stream.of("ipfix-information-elements", "ipfix-version-numbers", "ipfix-set-ids", "ipfix-information-element-data-types", "ipfix-information-element-semantics", "ipfix-information-element-units", "ipfix-structured-data-types-semantics"))
                        .allSatisfy(registryId -> Assertions.assertThat(registry.getRegistryById(registryId)).isNotNull());

        Assertions.assertThat(registry.getRegistryById("ipfix-information-elements"))
                .isNotNull()
                .satisfies(elementsRegistry -> {
                    Assertions.assertThat(elementsRegistry.getRecords()).isNotEmpty();
                    Assertions.assertThat(elementsRegistry.getRecords()).allSatisfy(record -> {
                        if (record.getElementId() != null) {
                            Assertions.assertThat(record.getElementId()).isGreaterThanOrEqualTo(0);
                        }
                        if (record.getElementId() != null && record.getElementId() == 0) {
                            Assertions.assertThat(record.getName()).isEqualTo("Reserved");
                        } else {
                            Assertions.assertThat(record.getName()).isNotNull();
                        }
                    });
        });
    }
}