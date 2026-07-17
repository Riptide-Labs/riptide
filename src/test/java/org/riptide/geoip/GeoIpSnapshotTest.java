/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.geoip;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider detection, decoding, merge order, and override semantics against the self-generated
 * fixtures in {@code src/test/resources/geoip/} (see {@code generate_fixtures.py} there).
 */
class GeoIpSnapshotTest {

    private static String fixture(final String name) {
        return Path.of("src/test/resources/geoip", name).toAbsolutePath().toString();
    }

    private static GeoIpSnapshot open(final List<String> paths, final Map<String, GeoIpConfig.GeoOverride> overrides) {
        return GeoIpSnapshot.open(paths, GeoIpConfig.parse(overrides));
    }

    @Test
    void maxMindGeoAndAsnDatabasesCompose() throws Exception {
        final var snapshot = open(List.of(fixture("geolite2-asn-test.mmdb"), fixture("geolite2-city-test.mmdb")), Map.of());

        final GeoInfo info = snapshot.lookup(InetAddress.getByName("203.0.113.7"));

        assertThat(info.country()).isEqualTo("DE");
        assertThat(info.city()).isEqualTo("Fulda");
        assertThat(info.asn()).isEqualTo(64500L);
        assertThat(info.asOrg()).isEqualTo("Example Org");
    }

    @Test
    void maxMindIpv6Lookup() throws Exception {
        final var snapshot = open(List.of(fixture("geolite2-city-test.mmdb")), Map.of());

        final GeoInfo info = snapshot.lookup(InetAddress.getByName("2001:db8::1"));

        assertThat(info.country()).isEqualTo("NL");
        assertThat(info.city()).isEqualTo("Amsterdam");
    }

    @Test
    void ipinfoDatabaseIsDetectedAndDecoded() throws Exception {
        final var snapshot = open(List.of(fixture("ipinfo-test.mmdb")), Map.of());

        final GeoInfo info = snapshot.lookup(InetAddress.getByName("192.0.2.5"));

        assertThat(info.country()).isEqualTo("US");
        assertThat(info.city()).isEqualTo("Ashburn");
        assertThat(info.asn()).isEqualTo(64501L);
        assertThat(info.asOrg()).isEqualTo("IPinfo Test Net");
    }

    @Test
    void ipinfoRegionFallsInForMissingCity() throws Exception {
        final var snapshot = open(List.of(fixture("ipinfo-test.mmdb")), Map.of());

        assertThat(snapshot.lookup(InetAddress.getByName("198.51.100.5")).city()).isEqualTo("New South Wales");
    }

    @Test
    void laterFileWinsPerField() throws Exception {
        final var snapshot = open(
                List.of(fixture("geolite2-city-test.mmdb"), fixture("geolite2-city-test2.mmdb")), Map.of());

        final GeoInfo info = snapshot.lookup(InetAddress.getByName("203.0.113.7"));

        assertThat(info.country()).isEqualTo("US");
        assertThat(info.city()).isEqualTo("Ashburn");
    }

    @Test
    void uncoveredAddressResolvesEmpty() throws Exception {
        final var snapshot = open(List.of(fixture("geolite2-city-test.mmdb")), Map.of());

        assertThat(snapshot.lookup(InetAddress.getByName("8.8.8.8")).isEmpty()).isTrue();
    }

    @Test
    void overridePinsOnlyItsSetFields() throws Exception {
        final var snapshot = open(List.of(fixture("geolite2-city-test.mmdb")),
                Map.of("203.0.113.0/24", new GeoIpConfig.GeoOverride(null, "Homelab", null, null)));

        final GeoInfo info = snapshot.lookup(InetAddress.getByName("203.0.113.7"));

        assertThat(info.city()).isEqualTo("Homelab");
        assertThat(info.country()).isEqualTo("DE"); // unset fields fall through to the database
    }

    @Test
    void overrideWinsOverDatabase() throws Exception {
        final var snapshot = open(List.of(fixture("geolite2-city-test.mmdb")),
                Map.of("203.0.113.0/24", new GeoIpConfig.GeoOverride("FR", null, null, null)));

        assertThat(snapshot.lookup(InetAddress.getByName("203.0.113.7")).country()).isEqualTo("FR");
    }

    @Test
    void longestOverridePrefixDecides() throws Exception {
        final var snapshot = open(List.of(), Map.of(
                "10.0.0.0/8", new GeoIpConfig.GeoOverride("AA", null, null, null),
                "10.20.0.0/16", new GeoIpConfig.GeoOverride("BB", null, null, null)));

        assertThat(snapshot.lookup(InetAddress.getByName("10.20.30.5")).country()).isEqualTo("BB");
        assertThat(snapshot.lookup(InetAddress.getByName("10.99.0.1")).country()).isEqualTo("AA");
    }

    @Test
    void hostFormOverridesCanonicalizeAndCollide() {
        assertThat(GeoIpConfig.parse(Map.of("10.0.0.5/24", new GeoIpConfig.GeoOverride("DE", null, null, null))))
                .singleElement()
                .satisfies(parsed -> assertThat(parsed.prefix().toString()).isEqualTo("10.0.0.0/24"));

        assertThatThrownBy(() -> GeoIpConfig.parse(Map.of(
                "10.0.0.5/24", new GeoIpConfig.GeoOverride("DE", null, null, null),
                "10.0.0.0/24", new GeoIpConfig.GeoOverride("FR", null, null, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same prefix block");
    }

    @Test
    void lookupErrorDegradesToEmptyNotException(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path tmp) throws Exception {
        // Truncating the file under an open memory-mapped reader makes every subsequent
        // lookup throw ("search tree is corrupt") — the mid-lookup reader error the spec's
        // degrade-not-drop scenario describes. lookup() must swallow it and resolve empty.
        final java.nio.file.Path live = tmp.resolve("geo.mmdb");
        java.nio.file.Files.copy(java.nio.file.Path.of(fixture("geolite2-city-test.mmdb")), live);
        final var snapshot = open(List.of(live.toString()), Map.of());
        java.nio.file.Files.writeString(live, "truncated");

        assertThat(snapshot.lookup(InetAddress.getByName("203.0.113.7")).isEmpty()).isTrue();
    }

    @Test
    void missingFileIsSkippedNotFatal() throws Exception {
        final var snapshot = open(List.of(fixture("does-not-exist.mmdb"), fixture("geolite2-city-test.mmdb")), Map.of());

        assertThat(snapshot.lookup(InetAddress.getByName("203.0.113.7")).country()).isEqualTo("DE");
    }

}
