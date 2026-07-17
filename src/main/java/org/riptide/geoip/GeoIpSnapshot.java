/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.geoip;

import inet.ipaddr.IPAddressString;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable serving state for GeoIP lookups: the open databases (config order) and the parsed
 * overrides. Built by {@link #open}, swapped atomically by the enricher's refresh — lookups
 * always see one snapshot, old or new, never a mix.
 *
 * <p>A configured file that is missing or unreadable is skipped with a warning (its state is
 * still fingerprinted, so a later refresh picks it up once it appears or changes) — enrichment
 * degrades, it never fails startup.</p>
 */
@Slf4j
final class GeoIpSnapshot {

    static final GeoIpSnapshot EMPTY = new GeoIpSnapshot(List.of(), List.of(), Map.of(), 0);

    private final List<GeoIpDatabase> databases;
    private final List<GeoIpConfig.ParsedOverride> overrides;

    /** Path → last-modified millis (-1 when missing) at open time. */
    private final Map<String, Long> fingerprint;

    /** Files that existed but could not be opened — a degraded open must not replace good state. */
    private final int openFailures;

    private GeoIpSnapshot(final List<GeoIpDatabase> databases,
                          final List<GeoIpConfig.ParsedOverride> overrides,
                          final Map<String, Long> fingerprint,
                          final int openFailures) {
        this.databases = databases;
        this.overrides = overrides;
        this.fingerprint = fingerprint;
        this.openFailures = openFailures;
    }

    static GeoIpSnapshot open(final List<String> paths, final List<GeoIpConfig.ParsedOverride> overrides) {
        final List<GeoIpDatabase> databases = new ArrayList<>(paths.size());
        final Map<String, Long> fingerprint = fingerprintOf(paths);
        int openFailures = 0;
        for (final String path : paths) {
            final File file = new File(path);
            if (!file.exists()) {
                log.warn("GeoIP database {} does not exist (yet) — skipping, a later refresh picks it up", path);
                continue;
            }
            try {
                databases.add(GeoIpDatabase.open(file));
            } catch (final IOException | RuntimeException e) {
                openFailures++;
                log.warn("GeoIP database {} could not be opened — skipping: {}", path, e.getMessage());
            }
        }
        return new GeoIpSnapshot(List.copyOf(databases), overrides, fingerprint, openFailures);
    }

    /** The on-disk state of the configured paths, comparable across time. */
    static Map<String, Long> fingerprintOf(final List<String> paths) {
        final Map<String, Long> fingerprint = new LinkedHashMap<>();
        for (final String path : paths) {
            final File file = new File(path);
            fingerprint.put(path, file.exists() ? file.lastModified() : -1L);
        }
        return Map.copyOf(fingerprint);
    }

    Map<String, Long> fingerprint() {
        return this.fingerprint;
    }

    int openFailures() {
        return this.openFailures;
    }

    /**
     * Resolve one address: every database in order (later non-null fields win), then the
     * longest-prefix override pins its set fields on top.
     */
    GeoInfo lookup(final InetAddress address) {
        GeoInfo result = GeoInfo.EMPTY;
        for (final GeoIpDatabase database : this.databases) {
            try {
                result = result.overlay(database.lookup(address));
            } catch (final IOException | RuntimeException e) {
                log.warn("GeoIP lookup failed for {}: {}", address.getHostAddress(), e.getMessage());
            }
        }
        final Optional<GeoInfo> override = matchOverride(address);
        if (override.isPresent()) {
            result = result.overlay(override.get());
        }
        return result;
    }

    private Optional<GeoInfo> matchOverride(final InetAddress address) {
        final IPAddressString candidate = new IPAddressString(address.getHostAddress());
        return this.overrides.stream()
                .filter(entry -> entry.prefix().contains(candidate))
                .max(Comparator.comparingInt(GeoIpConfig.ParsedOverride::prefixLength))
                .map(GeoIpConfig.ParsedOverride::info);
    }

    boolean isEmpty() {
        return this.databases.isEmpty() && this.overrides.isEmpty();
    }

    void close() {
        for (final GeoIpDatabase database : this.databases) {
            try {
                database.close();
            } catch (final IOException e) {
                log.debug("Closing GeoIP database failed: {}", e.getMessage());
            }
        }
    }
}
