/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.node;

import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.pipeline.EnrichedFlow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The semantics seam bound to the path a flow actually travels: configuration, through
 * the loader, into a view, through {@link ExporterNameEnricher}, onto the field a
 * dashboard reads.
 *
 * <p>The other three bindings each stop short of that. Two start from an already-built
 * entry, and the loader-level one added with prefix support stops at the view. This one
 * answers {@code matchedName} with the {@code exporterName} the enricher stamped, so a
 * cutover that resolved correctly but stamped the wrong field, stamped nothing, or
 * stamped only the first flow of a batch would fail the contract rather than pass it.
 * That gap is exactly what made a green contract suite say nothing about the consumer
 * cutover.</p>
 */
final class EnricherExporterMatchSemantics implements ExporterMatchSemantics {

    private static final SnmpProfilesConfig NO_PROFILES = new SnmpProfilesConfig(Map.of(), Map.of());

    @Override
    public Matcher build(final List<Entry> entries) {
        final StringBuilder yaml = new StringBuilder("riptide:\n  exporters:\n");
        for (final Entry entry : entries) {
            yaml.append("    \"").append(entry.name()).append("\":\n");
            if (entry.subnet() != null) {
                yaml.append("      address: \"").append(entry.subnet()).append("\"\n");
            }
            if (entry.observationDomainPin() != null) {
                yaml.append("      observation-domain: ").append(entry.observationDomainPin()).append('\n');
            }
        }
        final Inventory inventory = new Inventory(NO_PROFILES, new InventoryConfig());
        inventory.swap(InventoryLoader.parse(NO_PROFILES, yaml.toString(), "contract.yaml"));
        final ExporterNameEnricher enricher = new ExporterNameEnricher(inventory);

        return identity -> {
            // two flows, not one: a stamp that only reached the first would otherwise pass
            final var flows = List.of(EnrichedFlow.builder().build(), EnrichedFlow.builder().build());
            try {
                enricher.enrich(new org.riptide.pipeline.Source("default", identity), flows).get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (final java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e);
            }
            final String stamped = flows.getFirst().getExporterName();
            if (stamped != null && !stamped.equals(flows.getLast().getExporterName())) {
                throw new IllegalStateException("the batch was stamped inconsistently: "
                        + stamped + " then " + flows.getLast().getExporterName());
            }
            return Optional.ofNullable(stamped);
        };
    }
}
