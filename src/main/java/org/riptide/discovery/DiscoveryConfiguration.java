/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.riptide.inventory.FileInventoryDocument;
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
                                           final SecretResolvers secretResolvers) {
        return new DiscoveryClient(config, secretResolvers);
    }

    /**
     * Primary, so {@code Inventory} takes the composed document without knowing discovery exists,
     * and {@code ConfigFileReloader}'s two {@code rebuildAndSwap} calls reach it through
     * {@code Inventory}. {@code InventoryFileReloader} is the one consumer that does know: it
     * injects this type by name to watch it, because a lookup by {@code FileWatchTrigger.Source}
     * would also find the classification rule reloader's source.
     */
    @Bean
    @Primary
    public ComposedInventoryDocument composedInventoryDocument(final FileInventoryDocument file,
                                                               final DiscoveryClient client,
                                                               final DiscoveryConfig config,
                                                               final MetricRegistry metrics) {
        return new ComposedInventoryDocument(file, client::fetch, client::describe, config, metrics);
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
