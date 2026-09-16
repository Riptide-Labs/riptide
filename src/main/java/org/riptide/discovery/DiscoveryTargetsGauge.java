/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.riptide.inventory.Inventory;

import java.util.Objects;

/**
 * {@code discovery.targets}: how many devices discovery is enriching right now.
 *
 * <p><b>Derived, not pushed.</b> The value is read from the published inventory when the gauge is
 * scraped, so there is no moment at which anything has to remember to update it. That matters
 * because a candidate is published from three places — boot, the inventory watcher, and a
 * credential rotation's rebuild — and a pushed value would have to be updated at all three. This
 * project has repeatedly shipped defects from updating only the site a report pointed at. A derived
 * value has no site to miss.</p>
 *
 * <p>It also answers the defect this class exists for (#807). The gauge used to be set while the
 * candidate was being composed, which is before the merge, before validation, before the regression
 * guard and before any swap. A candidate the guard refused still moved it, so an operator was
 * sometimes reading what was serving and sometimes reading a render that never went live. A refused
 * candidate never becomes the published snapshot, so a derived gauge cannot describe one.</p>
 *
 * <p><b>Why the serving exporter count is the right number.</b> With discovery enabled the inventory
 * file may not declare an exporters tree at all — {@link ComposedInventoryDocument} refuses one — so
 * every exporter in the serving snapshot came from discovery and the two counts are the same. That
 * equivalence is load-bearing: were the file ever allowed to contribute exporters, this gauge would
 * quietly start counting them too.</p>
 *
 * <p><b>Its sibling means something else on purpose.</b> {@code discovery.skipped} stays a
 * render-time observation, set by {@link ComposedInventoryDocument}, because a device the endpoint
 * keeps offering without a usable address is worth seeing precisely while the candidate around it is
 * being refused. So the two gauges can legitimately disagree during a refusal. That is documented
 * rather than left to be rediscovered.</p>
 */
public final class DiscoveryTargetsGauge implements Gauge<Integer> {

    static final String NAME = "discovery.targets";

    private final Inventory inventory;

    /**
     * Registers the gauge against the inventory whose published snapshot it reports.
     *
     * <p>Registered here rather than on {@link ComposedInventoryDocument} because
     * {@link Inventory} is constructed <em>with</em> that document, so the document cannot hold an
     * {@code Inventory} without a cycle.</p>
     */
    public DiscoveryTargetsGauge(final Inventory inventory, final MetricRegistry metrics) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(metrics, "metrics");
        // remove-then-register, not Dropwizard's get-or-create: a restarted context would otherwise
        // be handed the old instance, permanently reading a dead Inventory
        metrics.remove(NAME);
        metrics.register(NAME, this);
    }

    /**
     * The published inventory's exporter count.
     *
     * <p>Runs on whichever thread scrapes, so it must not lock or perform IO.
     * {@link Inventory#snapshot()} is one volatile read of an immutable object and is deliberately
     * unsynchronized, so a scrape cannot block behind a reload or a credential rotation.</p>
     */
    @Override
    public Integer getValue() {
        return this.inventory.snapshot().exporterCount();
    }
}
