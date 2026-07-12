/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.routing;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
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
 * wins). Keys are canonicalized to their prefix block at startup — the host form
 * {@code 10.0.0.5/24} means the same as {@code 10.0.0.0/24} — and two keys resolving to
 * the same block fail startup. {@code as-names} maps AS numbers to names for exporters
 * that already fill AS fields. The two compose: prefix fill first, then names apply to
 * whatever number the flow ends up with.</p>
 */
@ConfigurationProperties(prefix = "riptide.routing")
public class RoutingConfig {

    /** Prefix → AS number/organisation, e.g. {@code "203.0.113.0/24": {asn: 64500, org: "Example"}}. */
    @Getter
    @Setter
    private Map<String, PrefixInfo> prefixes = new HashMap<>();

    /** AS number → display name, applied to exporter-provided or prefix-filled numbers. */
    @Getter
    @Setter
    private Map<Long, String> asNames = new HashMap<>();

    private List<ParsedPrefix> parsed = List.of();

    public record PrefixInfo(Long asn, String org) {
    }

    private record ParsedPrefix(IPAddressString prefix, int prefixLength, PrefixInfo info) {
    }

    @PostConstruct
    void parsePrefixes() {
        final Map<String, String> seenBlocks = new HashMap<>();
        final List<ParsedPrefix> result = new ArrayList<>(this.prefixes.size());
        for (final Map.Entry<String, PrefixInfo> entry : this.prefixes.entrySet()) {
            final IPAddressString raw = new IPAddressString(entry.getKey());
            if (raw.getAddress() == null) {
                throw new IllegalStateException("riptide.routing.prefixes: '%s' is not a valid prefix".formatted(entry.getKey()));
            }
            // canonicalize: the host form 10.0.0.5/24 must match its whole /24 block,
            // not just itself (IPAddressString.contains treats a host-with-prefix as a
            // single address)
            final IPAddress block = raw.getAddress().toPrefixBlock();
            final IPAddressString canonical = new IPAddressString(block.toString());
            final String other = seenBlocks.putIfAbsent(block.toString(), entry.getKey());
            if (other != null) {
                throw new IllegalStateException(("riptide.routing.prefixes: '%s' and '%s' are the same prefix block (%s) "
                        + "— matching between them would be arbitrary. Keep one.")
                        .formatted(other, entry.getKey(), block));
            }
            final Integer length = block.getNetworkPrefixLength();
            result.add(new ParsedPrefix(canonical, length != null ? length : block.getBitCount(), entry.getValue()));
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
