/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryDocument;
import org.riptide.secrets.SecretResolvers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.PrintWriter;
import java.io.StringWriter;
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

        /** Default trust: this fixture pins the discovery gate, not what the read trusts. */
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

    @Test
    void withTheUrlUnsetNothingIsCreatedAndNoDiscoveryGaugeExists() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ComposedInventoryDocument.class);
            assertThat(context).doesNotHaveBean(DiscoveryEndpoints.class);
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
                    assertThat(context).doesNotHaveBean(DiscoveryEndpoints.class);
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

    /**
     * The native source emits the NetBox label names and the renderer must read the same ones, so
     * a customised list would match no label on any device. Every device would then fall through to
     * its name, fail the strict address check, be skipped, and boot would die with "yielded no
     * exporter entries" naming neither key.
     */
    @Test
    void customisedAddressLabelsAreRefusedAgainstTheNativeSource() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/api/dcim/devices/",
                        "riptide.discovery.type=netbox-api",
                        "riptide.discovery.address-labels=__meta_custom_ip")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("riptide.discovery.address-labels")
                        .hasMessageContaining("netbox-api")
                        .hasMessageContaining("prometheus-sd"));
    }

    @Test
    void theDefaultAddressLabelsAreTheOnesTheNativeSourceEmits() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/api/dcim/devices/",
                        "riptide.discovery.type=netbox-api")
                .run(context -> assertThat(context)
                        .as("the refusal must not fire on an operator who customised nothing")
                        .hasNotFailed());
    }

    @Test
    void customisedAddressLabelsStayAllowedForTheServiceDiscoverySource() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices",
                        "riptide.discovery.address-labels=__meta_custom_ip")
                .run(context -> assertThat(context)
                        .as("a non-NetBox producer chooses its own label names")
                        .hasNotFailed());
    }

    /**
     * Selecting the mapped source without the paths it needs fails at startup, naming the key.
     *
     * <p>Surfacing this at the first poll instead would arrive as an empty result, and the refusal
     * for an empty result names a filter — sending an operator to look at a key they never set
     * (#800).</p>
     */
    @Test
    void theMappedSourceRefusesEachMissingPathAtStartupNamingIt() {
        // items is deliberately not here: unset means the response is itself the array, which is a
        // shape no path can name and the commonest one an endpoint has
        final Map<String, String> complete = Map.of(
                "riptide.discovery.mapping.name", "hostname",
                "riptide.discovery.mapping.address", "mgmt_ip");

        for (final String missing : complete.keySet()) {
            final List<String> properties = new java.util.ArrayList<>(List.of(
                    "riptide.discovery.url=http://127.0.0.1:9/devices",
                    "riptide.discovery.type=mapped-json"));
            complete.forEach((key, value) -> {
                if (!key.equals(missing)) {
                    properties.add(key + "=" + value);
                }
            });

            this.runner.withPropertyValues(properties.toArray(new String[0]))
                    .run(context -> assertThat(context)
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining(missing));
        }
    }

    @Test
    void theMappedSourceStartsWithEveryRequiredPathAndNoNextPath() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices",
                        "riptide.discovery.type=mapped-json",
                        "riptide.discovery.mapping.items=results",
                        "riptide.discovery.mapping.name=hostname",
                        "riptide.discovery.mapping.address=mgmt_ip")
                .run(context -> {
                    assertThat(context)
                            .as("the next path is optional: unset means a single request")
                            .hasNotFailed();
                    assertThat(onlySource(context)).isInstanceOf(MappedJsonSource.class);
                });
    }

    @Test
    void theMappedSourceStartsWithNoItemsPathBecauseTheResponseMayBeTheArray() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices",
                        "riptide.discovery.type=mapped-json",
                        "riptide.discovery.mapping.name=hostname",
                        "riptide.discovery.mapping.address=mgmt_ip")
                .run(context -> assertThat(context)
                        .as("a bare top-level array is a shape no path can name")
                        .hasNotFailed());
    }

    @Test
    void anUnsetTypeStillSelectsTheServiceDiscoveryReader() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices")
                .run(context -> assertThat(onlySource(context))
                        .as("a third type must not change what an unset key selects")
                        .isInstanceOf(ServiceDiscoverySource.class));
    }

    @Test
    void withTheUrlSetTheComposedDocumentIsThePrimaryInventoryDocument() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ComposedInventoryDocument.class);
                    assertThat(context).hasSingleBean(DiscoveryEndpoints.class);
                    assertThat(context.getBean(InventoryDocument.class))
                            .isInstanceOf(ComposedInventoryDocument.class);
                    assertThat(context.getBean(MetricRegistry.class).getGauges().keySet())
                            .contains("discovery.targets", "discovery.skipped");
                });
    }

    /** The one source a single-endpoint configuration builds. */
    private static DiscoverySource onlySource(final AssertableApplicationContext context) {
        final List<DiscoveryEndpoints.Endpoint> endpoints = context.getBean(DiscoveryEndpoints.class).endpoints();
        assertThat(endpoints).hasSize(1);
        return endpoints.getFirst().source();
    }

    /**
     * A runner whose system environment holds exactly {@code variables}. Replaced in the context
     * factory, not an initializer: the runner registers the configuration class, and so evaluates
     * its condition, before any initializer runs.
     */
    private ApplicationContextRunner withEnvironment(final Map<String, Object> variables) {
        return new ApplicationContextRunner(() -> {
            final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            context.getEnvironment().getPropertySources().replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
            return context;
        }).withUserConfiguration(Collaborators.class, DiscoveryConfiguration.class);
    }

    @Test
    void twoListedEndpointsBuildTwoSourcesOfTheConfiguredType() {
        this.runner
                .withPropertyValues("riptide.discovery.urls[0]=http://127.0.0.1:9/api/dcim/devices/",
                        "riptide.discovery.urls[1]=http://127.0.0.1:9/api/virtualization/virtual-machines/",
                        "riptide.discovery.type=netbox-api")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final List<DiscoveryEndpoints.Endpoint> endpoints =
                            context.getBean(DiscoveryEndpoints.class).endpoints();
                    assertThat(endpoints).extracting(endpoint -> endpoint.describe().get())
                            .containsExactly("http://127.0.0.1:9/api/dcim/devices/",
                                    "http://127.0.0.1:9/api/virtualization/virtual-machines/");
                    assertThat(endpoints).extracting(DiscoveryEndpoints.Endpoint::source)
                            .allMatch(NetboxDeviceSource.class::isInstance);
                    assertThat(context.getBean(InventoryDocument.class).name())
                            .endsWith(" + http://127.0.0.1:9/api/dcim/devices/, "
                                    + "http://127.0.0.1:9/api/virtualization/virtual-machines/");
                });
    }

    @Test
    void theIndexedEnvironmentFormEnablesDiscovery() {
        withEnvironment(Map.of("RIPTIDE_DISCOVERY_URLS_0", "http://127.0.0.1:9/devices"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DiscoveryEndpoints.class).endpoints())
                            .extracting(endpoint -> endpoint.describe().get())
                            .containsExactly("http://127.0.0.1:9/devices");
                });
    }

    @Test
    void anExportedButEmptyListLeavesDiscoveryOff() {
        withEnvironment(Map.of("RIPTIDE_DISCOVERY_URLS", ""))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DiscoveryEndpoints.class);
                    assertThat(context.getBean(InventoryDocument.class)).isInstanceOf(FileInventoryDocument.class);
                });
    }

    /**
     * What Spring does with an empty element in a comma-separated list. It keeps it, so the entry
     * reaches the blank-entry refusal instead of being dropped without a word; the docs name the
     * indexed form for a URL that itself holds a comma.
     */
    @Test
    void anEmptyElementInACommaSeparatedListIsKeptAndRefused() {
        withEnvironment(Map.of("RIPTIDE_DISCOVERY_URLS", "http://127.0.0.1:9/a,,http://127.0.0.1:9/b"))
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageStartingWith("riptide.discovery.urls[1] is blank."));
    }

    @Test
    void bothEndpointKeysSetFailStartupNamingBoth() {
        this.runner
                .withPropertyValues("riptide.discovery.url=http://127.0.0.1:9/devices",
                        "riptide.discovery.urls[0]=http://127.0.0.1:9/vms")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessage("riptide.discovery.url and riptide.discovery.urls are both set. Set one of "
                                + "them: riptide.discovery.url for a single endpoint, riptide.discovery.urls "
                                + "for several."));
    }

    @Test
    void aBlankEntryInANonEmptyListFailsStartupNamingItsIndex() {
        this.runner
                .withPropertyValues("riptide.discovery.urls[0]=http://127.0.0.1:9/devices",
                        "riptide.discovery.urls[1]=  ")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessage("riptide.discovery.urls[1] is blank. Once riptide.discovery.urls holds an "
                                + "entry, every entry must be a usable URL: remove the blank one."));
    }

    @Test
    void aMalformedEntryIsRefusedNamingItsIndexWithTheCredentialRedacted() {
        this.runner
                .withPropertyValues("riptide.discovery.urls[0]=http://127.0.0.1:9/devices",
                        "riptide.discovery.urls[1]=http://svc:s3cret@netbox/api/ bad",
                        "riptide.discovery.type=netbox-api")
                .run(context -> {
                    assertThat(context).getFailure()
                            .hasStackTraceContaining("riptide.discovery.urls[1] is not a usable URL: "
                                    + "'http://***@netbox/api/ bad'");
                    final StringWriter trace = new StringWriter();
                    context.getStartupFailure().printStackTrace(new PrintWriter(trace));
                    assertThat(trace.toString()).doesNotContain("s3cret");
                });
    }
}
