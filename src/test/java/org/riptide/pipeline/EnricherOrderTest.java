/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

import org.junit.jupiter.api.Test;
import org.riptide.classification.ClassificationEnricher;
import org.riptide.clock.ClockCorrectionEnricher;
import org.riptide.geoip.GeoIpEnricher;
import org.riptide.locality.LocalityEnricher;
import org.riptide.routing.RoutingEnricher;
import org.riptide.snmp.SnmpEnricher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enricher chain order is a contract, not an accident of bean naming: {@link Pipeline} runs
 * the injected list sequentially, and GeoIP AS data is specified as the ladder's lowest rung —
 * it must see exporter- and routing-provided values first. This pins the effective sequence so
 * renaming or adding an enricher cannot silently reorder the chain.
 */
@SpringBootTest
public class EnricherOrderTest {

    @Autowired
    private List<Enricher> enrichers;

    @Test
    void chainOrderIsExplicit() {
        // HostnamesEnricher is disabled by default (riptide.enricher.hostnames.enabled=false);
        // its slot between ClockCorrection and Locality is pinned by EnricherOrder.HOSTNAMES.
        assertThat(enrichers).extracting(enricher -> (Class) enricher.getClass()).containsExactly(
                ClassificationEnricher.class,
                ClockCorrectionEnricher.class,
                LocalityEnricher.class,
                RoutingEnricher.class,
                GeoIpEnricher.class,
                SnmpEnricher.class);
    }
}
