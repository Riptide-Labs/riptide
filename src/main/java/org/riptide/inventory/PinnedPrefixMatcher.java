/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;
import inet.ipaddr.ipv6.IPv6AddressAssociativeTrie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Longest-prefix matching with observation-domain pinning, O(prefix length) per
 * lookup for CIDR-shaped entries, independent of entry count.
 *
 * <p>Semantics (guarded by the FR-2 characterisation suite): pinning partitions the
 * candidate pool before specificity ranks it. Any entry pinned to the flow's
 * domain that contains the address beats every wildcard entry, even a more specific
 * one; an entry pinned to a different domain competes in neither pool. A bare host
 * address is the most specific match possible. IPv4 and IPv6 never match across
 * families.</p>
 *
 * <p>Entries that are a single address or a proper CIDR prefix block live in
 * associative tries. Every other shape the config layer historically accepted (a
 * prefix with host bits set, matching only that one address; range and wildcard
 * forms; the {@code *} catch-all; unparseable strings, which match nothing)
 * keeps its {@link IPAddressString#contains} semantics and specificity rank in a
 * small side pool scanned linearly. The side pool is empty for CIDR-only
 * configurations, so the common case pays nothing for the compatibility.</p>
 *
 * <p>Instances are immutable and the builder is single-shot ({@code build()}
 * invalidates it), so publishing one behind a volatile reference and swapping whole
 * instances is the entire concurrency story.</p>
 *
 * <p>Standalone by design: this class depends only on inet.ipaddr and the JDK so the
 * 0.9 inventory snapshot can reuse it unchanged.</p>
 *
 * @param <T> the value resolved by a successful match
 */
public final class PinnedPrefixMatcher<T> {

    private final Map<Long, Pool<T>> pinned;
    private final Pool<T> wildcard;

    private PinnedPrefixMatcher(final Map<Long, Pool<T>> pinned, final Pool<T> wildcard) {
        this.pinned = Map.copyOf(pinned);
        this.wildcard = wildcard;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Resolves the most specific matching entry for the address, pinned pool first.
     *
     * @param address the address to match; {@code null} yields empty
     * @param domain the observation domain (or sFlow sub-agent ID) the flow arrived
     *         under, matched against entry pins
     */
    public Optional<T> lookup(final IPAddressString address, final long domain) {
        if (address == null) {
            return Optional.empty();
        }
        final IPAddress parsed = address.getAddress();
        if (!this.pinned.isEmpty()) {
            final Pool<T> pinnedPool = this.pinned.get(domain);
            if (pinnedPool != null) {
                final Named<T> hit = pinnedPool.match(address, parsed);
                if (hit != null) {
                    return Optional.of(hit.value());
                }
            }
        }
        return Optional.ofNullable(this.wildcard.match(address, parsed)).map(Named::value);
    }

    /** An entry value paired with its name, so collisions can name both parties. */
    private record Named<T>(String name, T value) {
    }

    /**
     * One entry outside the trie shapes, matched by {@link IPAddressString#contains}
     * with the specificity rank the pre-trie implementation used (explicit prefix
     * length, else full bit count, else 0 for the unparseable/catch-all forms).
     */
    private record SideEntry<T>(IPAddressString subnet, int rank, Named<T> named) {
    }

    /** A dual-stack trie pair plus the legacy-shape side pool; one per pinning pool. */
    private static final class Pool<T> {

        private final IPv4AddressAssociativeTrie<Named<T>> v4 = new IPv4AddressAssociativeTrie<>();
        private final IPv6AddressAssociativeTrie<Named<T>> v6 = new IPv6AddressAssociativeTrie<>();
        private final List<SideEntry<T>> side = new ArrayList<>();

        /** Inserts a trie-shaped entry, returning any displaced occupant of the same slot. */
        Named<T> put(final IPAddress block, final Named<T> named) {
            return block.isIPv4() ? this.v4.put(block.toIPv4(), named) : this.v6.put(block.toIPv6(), named);
        }

        void putSide(final IPAddressString subnet, final int rank, final Named<T> named) {
            this.side.add(new SideEntry<>(subnet, rank, named));
        }

        Named<T> match(final IPAddressString address, final IPAddress parsed) {
            final var trieNode = parsed == null ? null
                    : parsed.isIPv4()
                            ? this.v4.longestPrefixMatchNode(parsed.toIPv4())
                            : this.v6.longestPrefixMatchNode(parsed.toIPv6());
            Named<T> best = trieNode != null ? trieNode.getValue() : null;
            int bestRank = trieNode != null ? rankOf(trieNode.getKey()) : Integer.MIN_VALUE;
            for (final SideEntry<T> entry : this.side) {
                if (entry.rank() > bestRank && entry.subnet().contains(address)) {
                    best = entry.named();
                    bestRank = entry.rank();
                }
            }
            return best;
        }

        private static int rankOf(final IPAddress key) {
            final Integer prefix = key.getNetworkPrefixLength();
            return prefix != null ? prefix : key.getBitCount();
        }
    }

    /**
     * Collects entries and builds an immutable matcher; single-shot: {@code add}
     * after {@code build}, and any use after a duplicate failure, are rejected. An
     * entry name travels with each addition so duplicate errors can name both
     * parties without the matcher knowing what the values are.
     */
    public static final class Builder<T> {

        private Map<Long, Pool<T>> pinned = new HashMap<>();
        private Pool<T> wildcard = new Pool<>();
        private final Map<String, String> seen = new HashMap<>();

        private Builder() {
        }

        /**
         * Adds one entry: a single address or CIDR prefix block goes to the tries;
         * every other shape (host-bits-set prefix, range or wildcard form, catch-all,
         * unparseable) keeps its historical contains() semantics in the side pool.
         *
         * @param name the entry's name, used in duplicate error messages
         * @param prefix a prefix, bare host address, or legacy address form
         * @param domainPin the observation-domain pin, or {@code null} for wildcard
         * @param value resolved by a successful match on this entry
         * @throws IllegalStateException when the prefix is missing; when an entry
         *         covering the same canonical prefix with the same pinning was already
         *         added, naming both entries (the builder becomes unusable, since the
         *         colliding entry may already occupy its slot); or when the builder
         *         was already built
         */
        public Builder<T> add(final String name, final IPAddressString prefix, final Long domainPin, final T value) {
            requireUsable();
            if (prefix == null) {
                throw new IllegalStateException(
                        "Entry '%s' has no prefix — every entry needs one to match.".formatted(name));
            }
            final IPAddress parsed = prefix.getAddress();
            final String key = (parsed != null ? parsed.toPrefixBlock().toString() : String.valueOf(prefix))
                    + "@" + (domainPin != null ? domainPin : "wildcard");
            final String other = this.seen.putIfAbsent(key, name);
            if (other != null) {
                throw duplicate(other, name);
            }
            final Pool<T> pool = domainPin != null
                    ? this.pinned.computeIfAbsent(domainPin, domain -> new Pool<>())
                    : this.wildcard;
            final Named<T> named = new Named<>(name, value);
            if (parsed != null && isTrieShaped(parsed)) {
                // the trie slot is authoritative: it also catches spellings the string
                // key treats as distinct, like a bare host vs its explicit /32
                final Named<T> displaced = pool.put(parsed.toPrefixBlock(), named);
                if (displaced != null) {
                    throw duplicate(displaced.name(), name);
                }
            } else {
                pool.putSide(prefix, sideRank(prefix, parsed), named);
            }
            return this;
        }

        /** Builds the matcher and invalidates this builder. */
        public PinnedPrefixMatcher<T> build() {
            requireUsable();
            final PinnedPrefixMatcher<T> matcher = new PinnedPrefixMatcher<>(this.pinned, this.wildcard);
            invalidate();
            return matcher;
        }

        private void requireUsable() {
            if (this.wildcard == null) {
                throw new IllegalStateException(
                        "Builder no longer usable (built, or poisoned by a duplicate failure) — build a new one.");
            }
        }

        private void invalidate() {
            this.pinned = null;
            this.wildcard = null;
        }

        private static boolean isTrieShaped(final IPAddress parsed) {
            return !parsed.isMultiple() ? !parsed.isPrefixed() || parsed.isSinglePrefixBlock()
                    : parsed.isPrefixed() && parsed.isSinglePrefixBlock();
        }

        private static int sideRank(final IPAddressString prefix, final IPAddress parsed) {
            final Integer explicit = prefix.getNetworkPrefixLength();
            if (explicit != null) {
                return explicit;
            }
            return parsed != null ? parsed.getBitCount() : 0;
        }

        private IllegalStateException duplicate(final String first, final String second) {
            invalidate();
            return new IllegalStateException(
                    ("Ambiguous matcher entries: '%s' and '%s' resolve to the same canonical prefix with the same "
                            + "observation-domain pinning — matching between them would be arbitrary. "
                            + "Merge them or distinguish them by prefix or observation-domain.")
                            .formatted(first, second));
        }
    }
}
