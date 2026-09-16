/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryDocument;
import org.riptide.secrets.SecretResolvers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.SnmpProfilesConfig;
import java.util.Map;

/**
 * The condition on {@link DiscoveryConfiguration} is the single gate for discovery. These pin
 * both sides of it with a curated bean list rather than a full {@code @SpringBootTest} context:
 * the condition is a property of the configuration class, and nothing else in the application
 * decides it.
 */
class DiscoveryWiringTest {

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

    @Test
    void withTheUrlUnsetNothingIsCreatedAndNoDiscoveryGaugeExists() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ComposedInventoryDocument.class);
            assertThat(context).doesNotHaveBean(DiscoveryClient.class);
            assertThat(context.getBean(InventoryDocument.class))
                    .as("the file stays the only inventory document")
                    .isInstanceOf(FileInventoryDocument.class);
            assertThat(context.getBean(MetricRegistry.class).getGauges().keySet())
                    .as("no discovery.* gauge reads zero for a feature that is off")
                    .noneMatch(name -> name.startsWith("discovery."));
        });
    }

    /**
     * A blank value is off, not on-and-broken. {@code @ConditionalOnProperty} with no
     * {@code havingValue} matches an empty string, so this used to create the beans and then kill
     * startup with "not a usable URL: ''" — for an operator whose container image exports
     * {@code RIPTIDE_DISCOVERY_URL=""} because it exports every variable it knows about.
     *
     * <p>This pins the gate and only the gate. {@code hasNotFailed()} here is weak evidence about
     * booting, because these collaborators include no {@code Inventory} to read the document at
     * boot, so this context would start either way. {@code DiscoveryBlankUrlBootTest} is what
     * proves the refusal to boot is gone from the real object graph.</p>
     */
    @Test
    void withTheUrlBlankNothingIsCreatedAndTheContextStillStarts() {
        this.runner
                .withPropertyValues("riptide.discovery.url=   ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ComposedInventoryDocument.class);
                    assertThat(context).doesNotHaveBean(DiscoveryClient.class);
                    assertThat(context.getBean(InventoryDocument.class))
                            .as("the file stays the only inventory document")
                            .isInstanceOf(FileInventoryDocument.class);
                });
    }

    /**
     * A single quote in the URL is a value, never code. It is legal in a URL's path and query, and
     * a gate spelled {@code @ConditionalOnExpression("'${riptide.discovery.url:}'.trim() != ''")}
     * resolves the placeholder into the expression text before SpEL parses it: the value below
     * would close the opening literal early and fail the refresh with a {@code SpelParseException},
     * which names neither this key nor anything an operator can act on.
     *
     * <p>What is asserted is that the refresh survives. Which way the gate then decides is
     * secondary and is asserted here only because it is the truthful answer for a non-blank value:
     * the URL is carried to {@code DiscoveryConfig.endpoint()}, which is the one place allowed to
     * judge it, and which names the key when it refuses.</p>
     */
    @Test
    void aSingleQuoteInTheUrlIsAValueAndNotAnExpressionParseError() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://localhost:1/o'brien")
                .run(context -> {
                    assertThat(context)
                            .as("the condition must not parse the operator's value as code")
                            .hasNotFailed();
                    assertThat(context).hasSingleBean(ComposedInventoryDocument.class);
                });
    }

    @Test
    void withTheUrlSetTheComposedDocumentIsThePrimaryInventoryDocument() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ComposedInventoryDocument.class);
                    assertThat(context).hasSingleBean(DiscoveryClient.class);
                    assertThat(context.getBean(InventoryDocument.class))
                            .isInstanceOf(ComposedInventoryDocument.class);
                    assertThat(context.getBean(MetricRegistry.class).getGauges().keySet())
                            .contains("discovery.targets", "discovery.skipped");
                });
    }
}
