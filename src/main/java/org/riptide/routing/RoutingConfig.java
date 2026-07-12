/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.routing;

import inet.ipaddr.IPAddressString;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static BGP/routing mapping — the enrichment ladder's middle rung for AS data.
 *
 * <p>{@code prefixes} maps CIDR prefixes to AS number and organisation (longest prefix
 * wins; map keys are unique, so ambiguity cannot arise). {@code as-names} maps AS
 * numbers to names for exporters that already fill AS fields. The two compose: prefix
 * fill first, then names apply to whatever number the flow ends up with.</p>
 */
@Data
@ConfigurationProperties(prefix = "riptide.routing")
public class RoutingConfig {

    /** Prefix → AS number/organisation, e.g. {@code "203.0.113.0/24": {asn: 64500, org: "Example"}}. */
    private Map<String, PrefixInfo> prefixes = new HashMap<>();

    /** AS number → display name, applied to exporter-provided or prefix-filled numbers. */
    private Map<Long, String> asNames = new HashMap<>();

    private List<ParsedPrefix> parsed = List.of();

    public record PrefixInfo(Long asn, String org) {
    }

    record ParsedPrefix(IPAddressString prefix, int prefixLength, PrefixInfo info) {
    }

    @PostConstruct
    void parsePrefixes() {
        final List<ParsedPrefix> result = new ArrayList<>(this.prefixes.size());
        for (final Map.Entry<String, PrefixInfo> entry : this.prefixes.entrySet()) {
            final IPAddressString prefix = new IPAddressString(entry.getKey());
            if (prefix.getAddress() == null) {
                throw new IllegalStateException("riptide.routing.prefixes: '%s' is not a valid prefix".formatted(entry.getKey()));
            }
            final Integer length = prefix.getNetworkPrefixLength();
            result.add(new ParsedPrefix(prefix, length != null ? length : prefix.getAddress().getBitCount(), entry.getValue()));
        }
        this.parsed = List.copyOf(result);
    }

    /** Longest-prefix match, same routing-table semantics as node matching. */
    Optional<PrefixInfo> lookupPrefix(final IPAddressString address) {
        return this.parsed.stream()
                .filter(entry -> entry.prefix().contains(address))
                .max(Comparator.comparingInt(ParsedPrefix::prefixLength))
                .map(ParsedPrefix::info);
    }

    Optional<String> lookupAsName(final Long asn) {
        return Optional.ofNullable(asn).map(this.asNames::get);
    }

    boolean isEmpty() {
        return this.parsed.isEmpty() && this.asNames.isEmpty();
    }
}
