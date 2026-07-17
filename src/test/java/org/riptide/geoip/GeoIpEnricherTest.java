/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.geoip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.Source;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enricher semantics: geo fill, the AS ladder (GeoIP fills only zeros left by exporter and
 * routing), inertness when unconfigured, and the refresh swap. Plain unit tests — the enricher
 * only needs its config.
 */
class GeoIpEnricherTest {

    private static final Path FIXTURES = Path.of("src/test/resources/geoip");

    private static GeoIpConfig config(final List<String> databases) {
        final var config = new GeoIpConfig();
        config.setDatabases(databases);
        config.parseOverrides();
        return config;
    }

    private static EnrichedFlow flow(final String src, final String dst) throws Exception {
        return EnrichedFlow.builder()
                .srcAddr(InetAddress.getByName(src))
                .dstAddr(InetAddress.getByName(dst))
                .build();
    }

    private static void enrich(final GeoIpEnricher enricher, final EnrichedFlow flow) throws Exception {
        enricher.enrich(new Source("default", InetAddress.getByName("203.0.113.254")), List.of(flow)).get();
    }

    private static void replaceByMove(final Path tmp, final Path target, final byte[] content, final long mtimeOffset) throws Exception {
        final Path staged = tmp.resolve("staged-" + mtimeOffset);
        Files.write(staged, content);
        Files.setLastModifiedTime(staged, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + mtimeOffset));
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Test
    void fillsGeoAndAsFieldsForBothSides() throws Exception {
        final var enricher = new GeoIpEnricher(config(List.of(
                FIXTURES.resolve("geolite2-asn-test.mmdb").toString(),
                FIXTURES.resolve("geolite2-city-test.mmdb").toString())));
        enricher.start();
        try {
            final var flow = flow("203.0.113.7", "198.51.100.9");
            enrich(enricher, flow);

            assertThat(flow.getSrcCountry()).isEqualTo("DE");
            assertThat(flow.getSrcCity()).isEqualTo("Fulda");
            assertThat(flow.getSrcAs()).isEqualTo(64500L);
            assertThat(flow.getSrcAsOrg()).isEqualTo("Example Org");
            // dst side: covered by the ASN db only
            assertThat(flow.getDstCountry()).isNull();
            assertThat(flow.getDstAs()).isEqualTo(15169L);
            assertThat(flow.getDstAsOrg()).isEqualTo("Google LLC");
        } finally {
            enricher.stop();
        }
    }

    @Test
    void exporterOrRoutingProvidedAsWins() throws Exception {
        final var enricher = new GeoIpEnricher(config(List.of(FIXTURES.resolve("geolite2-asn-test.mmdb").toString())));
        enricher.start();
        try {
            // As after RoutingEnricher: the AS is already nonzero, and its org is set — GeoIP
            // must touch neither, and must not label the foreign AS with its own org.
            final var flow = flow("203.0.113.7", "198.51.100.9");
            flow.setSrcAs(65000L);
            flow.setDstAs(65001L);
            flow.setDstAsOrg("Routing Org");
            enrich(enricher, flow);

            assertThat(flow.getSrcAs()).isEqualTo(65000L);
            assertThat(flow.getSrcAsOrg()).isNull(); // no GeoIP org for a non-GeoIP AS number
            assertThat(flow.getDstAs()).isEqualTo(65001L);
            assertThat(flow.getDstAsOrg()).isEqualTo("Routing Org");
        } finally {
            enricher.stop();
        }
    }

    @Test
    void unconfiguredEnricherIsInert() throws Exception {
        final var enricher = new GeoIpEnricher(config(List.of()));
        enricher.start();
        try {
            final var flow = flow("203.0.113.7", "198.51.100.9");
            enrich(enricher, flow);

            assertThat(flow.getSrcCountry()).isNull();
            assertThat(flow.getSrcAs()).isNull();
        } finally {
            enricher.stop();
        }
    }

    @Test
    void refreshSwapsChangedDatabaseAndKeepsServingOnCorruptReplacement(@TempDir final Path tmp) throws Exception {
        final Path live = tmp.resolve("geo.mmdb");
        Files.copy(FIXTURES.resolve("geolite2-city-test.mmdb"), live);

        final var enricher = new GeoIpEnricher(config(List.of(live.toString())));
        enricher.start();
        try {
            final var before = flow("203.0.113.7", "198.51.100.9");
            enrich(enricher, before);
            assertThat(before.getSrcCountry()).isEqualTo("DE");

            // Replace with the overlapping second fixture (different country) and refresh.
            // Temp-file + atomic move mirrors how geoipupdate replaces databases — the old
            // snapshot's mmap stays on the old inode, so it serves until the swap completes.
            replaceByMove(tmp, live, Files.readAllBytes(FIXTURES.resolve("geolite2-city-test2.mmdb")), 1000);
            enricher.refresh();

            final var after = flow("203.0.113.7", "198.51.100.9");
            enrich(enricher, after);
            assertThat(after.getSrcCountry()).isEqualTo("US");

            // Corrupt replacement: refresh keeps serving the last good snapshot.
            replaceByMove(tmp, live, "not an mmdb".getBytes(), 2000);
            enricher.refresh();

            final var still = flow("203.0.113.7", "198.51.100.9");
            enrich(enricher, still);
            assertThat(still.getSrcCountry()).isEqualTo("US");
        } finally {
            enricher.stop();
        }
    }

    @Test
    void lateAppearingDatabaseStartsServingAfterRefresh(@TempDir final Path tmp) throws Exception {
        final Path pending = tmp.resolve("pending.mmdb");

        final var enricher = new GeoIpEnricher(config(List.of(pending.toString())));
        enricher.start();
        try {
            final var before = flow("203.0.113.7", "198.51.100.9");
            enrich(enricher, before);
            assertThat(before.getSrcCountry()).isNull();

            Files.copy(FIXTURES.resolve("geolite2-city-test.mmdb"), pending);
            enricher.refresh();

            final var after = flow("203.0.113.7", "198.51.100.9");
            enrich(enricher, after);
            assertThat(after.getSrcCountry()).isEqualTo("DE");
        } finally {
            enricher.stop();
        }
    }

    @Test
    void overridesAloneWork() throws Exception {
        final var config = new GeoIpConfig();
        config.setOverrides(Map.of("192.168.0.0/16", new GeoIpConfig.GeoOverride("DE", "Homelab", null, null)));
        config.parseOverrides();
        final var enricher = new GeoIpEnricher(config);
        enricher.start();
        try {
            final var flow = flow("192.168.10.5", "192.168.13.40");
            enrich(enricher, flow);

            assertThat(flow.getSrcCountry()).isEqualTo("DE");
            assertThat(flow.getSrcCity()).isEqualTo("Homelab");
            assertThat(flow.getDstCountry()).isEqualTo("DE");
        } finally {
            enricher.stop();
        }
    }
}
