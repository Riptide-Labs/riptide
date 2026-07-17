/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.geoip;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GeoIP enrichment configuration — the ladder's global-database rung plus its manual pin.
 *
 * <p>{@code databases} is an ordered list of {@code .mmdb} paths (MaxMind or IPinfo,
 * auto-detected per file); a lookup consults every file and a later file's non-null fields win.
 * {@code overrides} maps CIDR prefixes to partial geo data that pins the set fields over
 * whatever the databases resolve (longest prefix wins). Override keys follow the same
 * canonicalization contract as {@code riptide.routing.prefixes}: host forms collapse to their
 * prefix block, and two keys resolving to the same block fail startup.</p>
 */
@ConfigurationProperties(prefix = "riptide.geoip")
public class GeoIpConfig {

    /** Ordered {@code .mmdb} paths; later files override earlier ones per field. */
    @Getter
    @Setter
    private List<String> databases = new ArrayList<>();

    /** How often to check the database files for changes (mtime-based). */
    @Getter
    @Setter
    private java.time.Duration refreshInterval = java.time.Duration.ofMinutes(5);

    /** Prefix → partial geo pin, e.g. {@code "192.168.0.0/16": {country: DE, city: Homelab}}. */
    @Getter
    @Setter
    private Map<String, GeoOverride> overrides = new HashMap<>();

    private volatile List<ParsedOverride> parsedOverrides = List.of();

    public record GeoOverride(String country, String city, Long asn, String org) {
    }

    record ParsedOverride(IPAddressString prefix, int prefixLength, GeoInfo info) {
    }

    /** Validates the boot-time bind; a bad override prefix is a config error and fails startup. */
    @PostConstruct
    public void parseOverrides() {
        this.parsedOverrides = parse(this.overrides);
    }

    /** Validates a candidate without touching serving state; throws on bad config. */
    static List<ParsedOverride> parse(final Map<String, GeoOverride> overrides) {
        final Map<String, String> seenBlocks = new HashMap<>();
        final List<ParsedOverride> result = new ArrayList<>(overrides.size());
        for (final Map.Entry<String, GeoOverride> entry : overrides.entrySet()) {
            final IPAddressString raw = new IPAddressString(entry.getKey());
            if (raw.getAddress() == null) {
                throw new IllegalStateException("riptide.geoip.overrides: '%s' is not a valid prefix".formatted(entry.getKey()));
            }
            // canonicalize: the host form 10.0.0.5/24 must match its whole /24 block (see
            // RoutingConfig — same contract)
            final IPAddress block = raw.getAddress().toPrefixBlock();
            final IPAddressString canonical = new IPAddressString(block.toString());
            final String other = seenBlocks.putIfAbsent(block.toString(), entry.getKey());
            if (other != null) {
                throw new IllegalStateException(("riptide.geoip.overrides: '%s' and '%s' are the same prefix block (%s) "
                        + "— matching between them would be arbitrary. Keep one.")
                        .formatted(other, entry.getKey(), block));
            }
            final Integer length = block.getNetworkPrefixLength();
            final GeoOverride o = entry.getValue();
            result.add(new ParsedOverride(canonical, length != null ? length : block.getBitCount(),
                    new GeoInfo(o.country(), o.city(), o.asn(), o.org())));
        }
        return List.copyOf(result);
    }

    List<ParsedOverride> parsedOverrides() {
        return this.parsedOverrides;
    }

    public boolean isUnconfigured() {
        return this.databases.isEmpty() && this.overrides.isEmpty();
    }
}
