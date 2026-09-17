/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryDocument;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.inventory.TestCredentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code discovery.targets} answers "how many devices is discovery enriching right now", so it has
 * to describe the published inventory and never a candidate that was composed and then rejected.
 * It is derived rather than pushed: there is no moment at which anything must remember to update
 * it, so none of the three publication paths can be missed.
 */
class DiscoveryTargetsGaugeTest {

    private static final String ONE_DEVICE = """
            [{"targets":["firewall-01"],
              "labels":{"__meta_netbox_name":"firewall-01",
                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
            """;

    private static final String TWO_DEVICES = """
            [{"targets":["firewall-01"],
              "labels":{"__meta_netbox_name":"firewall-01",
                        "__meta_netbox_primary_ip4":"10.0.0.1"}},
             {"targets":["firewall-02"],
              "labels":{"__meta_netbox_name":"firewall-02",
                        "__meta_netbox_primary_ip4":"10.0.0.2"}}]
            """;

    private static final String AGENTS = """
            riptide:
              snmp:
                agents:
                  "10.0.0.0/8":
                    credentials: corp-v3
            """;

    /** A file document answering fixed text, standing in for riptide.inventory.file. */
    private record FixedFile(String text) implements InventoryDocument {
        @Override
        public String name() {
            return "inventory.yaml";
        }
    }

    private static SnmpProfilesConfig profiles() {
        return new SnmpProfilesConfig(Map.of("corp-v3", TestCredentials.v3()), Map.of());
    }

    private static ComposedInventoryDocument composed(final String fileText,
                                                      final ServiceDiscoverySource.Fetcher fetcher) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        return new ComposedInventoryDocument(new FixedFile(fileText), new ServiceDiscoverySource(fetcher, () -> "the endpoint"), () -> "the endpoint", config,
                new MetricRegistry());
    }

    private static int gauge(final MetricRegistry metrics) {
        return (Integer) metrics.getGauges().get("discovery.targets").getValue();
    }

    @Test
    void aRefusedCandidateDoesNotMoveTheGauge() {
        final AtomicReference<String> answer = new AtomicReference<>(TWO_DEVICES);
        final ComposedInventoryDocument document =
                composed(AGENTS, () -> answer.get().getBytes(StandardCharsets.UTF_8));
        final Inventory inventory = new Inventory(profiles(), document);
        inventory.load();
        final MetricRegistry metrics = new MetricRegistry();
        new DiscoveryTargetsGauge(inventory, metrics);

        assertThat(gauge(metrics)).as("two devices are serving").isEqualTo(2);

        // the endpoint now answers with nothing usable, which the renderer refuses outright, so
        // no candidate is ever published and the two-device inventory keeps serving
        answer.set("[]");
        assertThatThrownBy(document::text).isInstanceOf(IllegalStateException.class);

        assertThat(gauge(metrics))
                .as("the gauge describes what is serving, not a candidate that was refused")
                .isEqualTo(2);
    }

    @Test
    void aPollThatFailsBeforeAnyCandidateDoesNotMoveTheGauge() {
        final AtomicReference<ServiceDiscoverySource.Fetcher> fetcher =
                new AtomicReference<>(() -> ONE_DEVICE.getBytes(StandardCharsets.UTF_8));
        final ComposedInventoryDocument document = composed(AGENTS, () -> fetcher.get().fetch());
        final Inventory inventory = new Inventory(profiles(), document);
        inventory.load();
        final MetricRegistry metrics = new MetricRegistry();
        new DiscoveryTargetsGauge(inventory, metrics);

        assertThat(gauge(metrics)).isEqualTo(1);

        fetcher.set(() -> {
            throw new IOException("connection refused");
        });
        assertThatThrownBy(document::text).isInstanceOf(IllegalStateException.class);

        assertThat(gauge(metrics))
                .as("an unreachable endpoint leaves the serving inventory, and the gauge, alone")
                .isEqualTo(1);
    }

    @Test
    void aPublishedCandidateMovesTheGauge() {
        final AtomicReference<String> answer = new AtomicReference<>(ONE_DEVICE);
        final ComposedInventoryDocument document =
                composed(AGENTS, () -> answer.get().getBytes(StandardCharsets.UTF_8));
        final Inventory inventory = new Inventory(profiles(), document);
        inventory.load();
        final MetricRegistry metrics = new MetricRegistry();
        new DiscoveryTargetsGauge(inventory, metrics);

        assertThat(gauge(metrics)).isEqualTo(1);

        // a credential rotation republishes through rebuildAndSwap, which is the path a pushed
        // value would most likely have missed: it never goes near the watcher
        answer.set(TWO_DEVICES);
        inventory.rebuildAndSwap(profiles());

        assertThat(gauge(metrics))
                .as("a gauge that never moves would pass the two tests above and still be useless")
                .isEqualTo(2);
    }

    @Test
    void aDegradedBootReportsZeroBecauseZeroIsWhatIsServing() {
        final ComposedInventoryDocument document = composed(AGENTS, () -> {
            throw new IOException("connection refused");
        });
        final Inventory inventory = new Inventory(profiles(), document);
        inventory.load();
        final MetricRegistry metrics = new MetricRegistry();
        new DiscoveryTargetsGauge(inventory, metrics);

        assertThat(inventory.snapshot().agentCount()).as("the file's ranges still serve").isEqualTo(1);
        assertThat(gauge(metrics))
                .as("no discovered exporter is serving, so zero is the honest answer")
                .isZero();
    }

    /**
     * The shape that actually distinguishes a derived gauge from the pushed one this replaced: a
     * candidate that renders SUCCESSFULLY and is then refused downstream. The two earlier refusal
     * tests both abort inside the renderer or the fetch, before the old code reached the line that
     * set the gauge, so neither could fail against it. This one can.
     *
     * <p>The file loses its agent tree without declaring it empty, which is what a torn write looks
     * like, so the regression guard refuses the whole candidate even though its two exporters
     * rendered cleanly. The pushed gauge would already have moved to 2.</p>
     */
    @Test
    void aCandidateThatRendersAndIsThenRefusedDoesNotMoveTheGauge() {
        final AtomicReference<String> fileText = new AtomicReference<>(AGENTS);
        final AtomicReference<String> answer = new AtomicReference<>(ONE_DEVICE);
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        // ONE registry, as production has: the document and the gauge share it, so a regression
        // that went back to registering discovery.targets from the document would be competing for
        // this very name and this test would see it
        final MetricRegistry metrics = new MetricRegistry();
        final ComposedInventoryDocument document = new ComposedInventoryDocument(
                new MutableFile(fileText),
                new ServiceDiscoverySource(() -> answer.get().getBytes(StandardCharsets.UTF_8), () -> "the endpoint"),
                () -> "the endpoint", config, metrics);
        final Inventory inventory = new Inventory(profiles(), document);
        inventory.load();
        new DiscoveryTargetsGauge(inventory, metrics);

        assertThat(gauge(metrics)).as("one device is serving").isEqualTo(1);

        // the endpoint now offers two devices, which render perfectly well, but the file has been
        // caught mid-write and its agent tree is gone without being declared empty
        answer.set(TWO_DEVICES);
        fileText.set("riptide:\n  snmp:\n    agents:\n");

        assertThat(inventory.rebuildAndSwap(profiles()))
                .as("the guard refuses the candidate, so nothing is published")
                .isNull();
        assertThat(inventory.snapshot().exporterCount())
                .as("the one-device inventory is still what serves")
                .isEqualTo(1);
        assertThat(gauge(metrics))
                .as("the render succeeded and produced 2; a gauge set during compose would say 2")
                .isEqualTo(1);
    }

    /** A file document whose text can change between reads, as a real file's does. */
    private static final class MutableFile implements InventoryDocument {

        private final AtomicReference<String> content;

        private MutableFile(final AtomicReference<String> content) {
            this.content = content;
        }

        @Override
        public String text() {
            return this.content.get();
        }

        @Override
        public String name() {
            return "inventory.yaml";
        }
    }
}
