/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.InventoryConfig;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.secrets.SecretResolvers;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.SnmpProfilesConfig;
import java.util.Map;

/**
 * {@link DiscoveryUrlSet}'s match outcome, read the way an operator actually reads it: through
 * the condition evaluation report that {@code --debug} output and {@code /actuator/conditions}
 * both surface. A plain {@code Condition} records nothing there; extending {@code
 * SpringBootCondition} is what makes "why did discovery not turn on" answerable, and this pins
 * that the recorded message actually says so, for every case {@link DiscoveryWiringTest} also
 * pins at the bean level.
 */
class DiscoveryUrlSetTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class, DiscoveryConfiguration.class);

    /** What {@link DiscoveryConfiguration}'s beans need, and nothing that would decide for it. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DiscoveryConfig.class, InventoryConfig.class})
    @Import(FileInventoryDocument.class)
    static class Collaborators {

        @Bean
        MetricRegistry metricRegistry() {
            return new MetricRegistry();
        }

        @Bean
        SecretResolvers secretResolvers() {
            return new SecretResolvers(List.of());
        }

        /** Default trust: this fixture pins the URL gate, not what the read trusts. */
        @Bean
        OutboundHttpTrust outboundHttpTrust() {
            return new OutboundHttpTrust();
        }

        /**
         * The inventory the target gauge reports. Built explicitly over the FILE document, not the
         * primary composed one: an Inventory holding the composed document would fetch the endpoint
         * during its @PostConstruct load, and this context's URL points at a host that does not
         * exist. What this fixture owes DiscoveryConfiguration is a collaborator, not a decision.
         */
        @Bean
        Inventory inventory(final FileInventoryDocument file) {
            return new Inventory(new SnmpProfilesConfig(Map.of(), Map.of()), file);
        }
    }

    /** The recorded outcome message for {@link DiscoveryConfiguration}'s condition. */
    private static String outcomeMessage(final ConfigurableApplicationContext context) {
        return ConditionEvaluationReport.get(context.getBeanFactory())
                .getConditionAndOutcomesBySource()
                .get(DiscoveryConfiguration.class.getName())
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("no recorded condition outcome for DiscoveryConfiguration"))
                .getOutcome()
                .getMessage();
    }

    @Test
    void unsetUrlRecordsANoMatchOutcomeNamingTheProperty() {
        this.runner.run(context -> assertThat(outcomeMessage(context))
                .as("an operator asking why discovery is off must see the property named")
                .contains(DiscoveryUrlSet.URL_PROPERTY)
                .containsIgnoringCase("not set"));
    }

    @Test
    void emptyUrlRecordsANoMatchOutcomeNamingTheProperty() {
        this.runner.withPropertyValues("riptide.discovery.url=").run(context -> {
            assertThat(outcomeMessage(context))
                    .contains(DiscoveryUrlSet.URL_PROPERTY)
                    .containsIgnoringCase("blank");
            assertThat(context).doesNotHaveBean(DiscoveryClient.class);
        });
    }

    @Test
    void whitespaceOnlyUrlRecordsANoMatchOutcomeNamingTheProperty() {
        this.runner.withPropertyValues("riptide.discovery.url=   ").run(context -> {
            assertThat(outcomeMessage(context))
                    .contains(DiscoveryUrlSet.URL_PROPERTY)
                    .containsIgnoringCase("blank");
            assertThat(context).doesNotHaveBean(DiscoveryClient.class);
        });
    }

    @Test
    void aNormalUrlRecordsAMatchOutcomeNamingTheProperty() {
        this.runner.withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices").run(context -> {
            assertThat(outcomeMessage(context))
                    .as("an operator confirming discovery is on must see why")
                    .contains(DiscoveryUrlSet.URL_PROPERTY)
                    .containsIgnoringCase("set and non-blank");
            assertThat(context).hasSingleBean(DiscoveryClient.class);
        });
    }

    /**
     * The regression this whole class exists to guard against a second time: a URL containing a
     * single quote must be read from the environment, never turned into expression text.
     */
    @Test
    void aUrlContainingAnApostropheRecordsAMatchOutcomeWithoutBeingParsedAsCode() {
        this.runner.withPropertyValues("riptide.discovery.url=http://localhost:1/o'brien").run(context -> {
            assertThat(context)
                    .as("the value must never become expression source")
                    .hasNotFailed();
            assertThat(outcomeMessage(context)).contains(DiscoveryUrlSet.URL_PROPERTY);
            assertThat(context).hasSingleBean(DiscoveryClient.class);
        });
    }
}
