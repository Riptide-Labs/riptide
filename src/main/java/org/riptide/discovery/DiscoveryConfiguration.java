/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.config.OutboundHttpTrust;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryDocument;
import org.riptide.secrets.SecretResolvers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires discovery only when {@code riptide.discovery.url} holds a non-blank value. With it unset
 * or blank nothing here is created, the {@link FileInventoryDocument} stays the only
 * {@link InventoryDocument}, and every path behaves exactly as it did before discovery existed.
 *
 * <p><b>Why not {@code @ConditionalOnProperty}.</b> That condition with no {@code havingValue}
 * matches an empty string, so {@code RIPTIDE_DISCOVERY_URL=""} — which a container image or a Helm
 * template exports whether or not it has a value — created these beans and then killed startup
 * with "not a usable URL: ''", for an operator who never asked for discovery. A collector that
 * will not start is worse than one that quietly does not discover. This stays the single gate:
 * there is deliberately no {@code isEnabled()} on {@code DiscoveryConfig} for the same rule to be
 * encoded a second time and disagree.</p>
 *
 * <p><b>Why the condition never quotes the operator's value.</b> The obvious spelling,
 * {@code @ConditionalOnExpression("'${riptide.discovery.url:}'.trim() != ''")}, resolves the
 * placeholder into the expression <em>text</em> before SpEL parses it, with no escaping. A URL is
 * allowed a single quote in its path or query, and {@code http://host/o'brien} then makes the
 * expression {@code 'http://host/o'brien'.trim() != ''} — a malformed literal that fails the
 * context refresh with a {@code SpelParseException} instead of this project's own "not a usable
 * URL" message. {@link DiscoveryUrlSet} takes the value from the environment at evaluation time,
 * so the operator's text is never expression source and nothing in it can be parsed as code.</p>
 */
@Configuration
@Conditional(DiscoveryUrlSet.class)
public class DiscoveryConfiguration {

    @Bean
    public DiscoveryClient discoveryClient(final DiscoveryConfig config,
                                           final SecretResolvers secretResolvers,
                                           final OutboundHttpTrust trust) {
        return new DiscoveryClient(config, secretResolvers, trust);
    }

    /**
     * The mapping paths, resolved at startup rather than at the first poll.
     *
     * <p>A missing path is a configuration error, and surfacing it at the first poll would arrive as
     * an empty result, whose refusal names a filter and sends an operator to a key they never set.
     * Each is named here so the message says which one.</p>
     */
    private static MappedJsonSource.MappingPaths mapping(final DiscoveryConfig.Mapping mapping) {
        return new MappedJsonSource.MappingPaths(
                mapping.getItems() == null || mapping.getItems().isBlank()
                        ? null
                        : JsonPath.of(mapping.getItems(), "riptide.discovery.mapping.items"),
                JsonPath.of(mapping.getName(), "riptide.discovery.mapping.name"),
                JsonPath.of(mapping.getAddress(), "riptide.discovery.mapping.address"),
                mapping.getNext() == null || mapping.getNext().isBlank()
                        ? null
                        : JsonPath.of(mapping.getNext(), "riptide.discovery.mapping.next"));
    }

    /**
     * The source {@code riptide.discovery.type} selects.
     *
     * <p>Two of the three read NetBox in the deployments this was built for; they differ in what
     * they speak to. One reads a Prometheus service discovery document, which on NetBox means a
     * plugin is installed. The second reads NetBox's own device API and needs nothing installed,
     * pages its results and accepts NetBox's filters. The third reads any JSON endpoint by paths the
     * operator writes, for a source of truth that is neither.</p>
     */
    @Bean
    public DiscoverySource discoverySource(final DiscoveryClient client, final DiscoveryConfig config) {
        return switch (config.sourceType()) {
            case PROMETHEUS_SD -> new ServiceDiscoverySource(client::fetch, client::describe);
            case NETBOX_API -> {
                // address-labels names the labels the renderer READS, and this source controls both
                // sides: it emits the NetBox names and the renderer must look for the same ones. An
                // operator who customised the key for a prometheus-sd producer and then switched
                // type would match no label on any device, so every one would fall through to the
                // device name, fail the strict address check, be skipped, and boot would die with
                // "yielded no exporter entries" naming neither key. Refused here instead.
                if (!NetboxDeviceSource.EMITTED_ADDRESS_LABELS.equals(config.getAddressLabels())) {
                    throw new IllegalStateException(
                            ("riptide.discovery.address-labels cannot be customised while "
                                    + "riptide.discovery.type is '%s': this source emits %s and the "
                                    + "renderer must read the same names. Leave the labels at their "
                                    + "default, or use type '%s' where the producer chooses them.")
                                    .formatted(DiscoverySourceType.NETBOX_API.key(),
                                            NetboxDeviceSource.EMITTED_ADDRESS_LABELS,
                                            DiscoverySourceType.PROMETHEUS_SD.key()));
                }
                yield new NetboxDeviceSource(
                        NetboxDeviceSource.firstPage(config.endpoint(), config.getFilter()),
                        client::fetchPage, client::describe);
            }
            // firstPage without the ordering: this endpoint is not NetBox, so NetBox's
            // pagination-stability term has no business being appended to it
            case MAPPED_JSON -> new MappedJsonSource(
                    NetboxDeviceSource.firstPage(config.endpoint(), config.getFilter(), false),
                    client::fetchPage, client::describe, mapping(config.getMapping()));
        };
    }

    /**
     * Primary, so {@code Inventory} takes the composed document without knowing discovery exists,
     * and {@code ConfigFileReloader}'s two {@code rebuildAndSwap} calls reach it through
     * {@code Inventory}. {@code InventoryFileReloader} watches it, and since #806 does so through
     * {@code PacedInventorySource} rather than this type: the interface is narrower than
     * {@code FileWatchTrigger.Source}, which the classification rule reloader's source also
     * implements, so the watcher cannot resolve to the wrong one and the configuration package no
     * longer names anything in this one.
     */
    @Bean
    @Primary
    public ComposedInventoryDocument composedInventoryDocument(final FileInventoryDocument file,
                                                               final DiscoveryClient client,
                                                               final DiscoverySource source,
                                                               final DiscoveryConfig config,
                                                               final MetricRegistry metrics) {
        return new ComposedInventoryDocument(file, source, client::describe, config, metrics);
    }

    /**
     * Registers {@code discovery.targets} against the published inventory.
     *
     * <p>Declared here, alongside the other discovery beans, so it exists exactly when discovery
     * does. Gating it separately would be a second condition that could disagree with the one on
     * this class. It takes {@link Inventory} rather than living on the composed document, which
     * {@code Inventory} is itself constructed with and so cannot depend on in return.</p>
     */
    @Bean
    public DiscoveryTargetsGauge discoveryTargetsGauge(final Inventory inventory,
                                                       final MetricRegistry metrics) {
        return new DiscoveryTargetsGauge(inventory, metrics);
    }
}
