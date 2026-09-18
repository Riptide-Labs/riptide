/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.riptide.inventory.StrictAddresses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps service discovery target groups onto exporter entries.
 *
 * <p><b>Why the mapping inverts.</b> For a NetBox device the service discovery target is the device
 * <em>name</em>, never an address: the plugin's serializer returns {@code [obj.name]}, and its own
 * test sets a primary IPv4, an IPv6 and an out-of-band IP and still asserts the target is the name.
 * Addresses are available only as labels, and every IP the plugin emits has already had its CIDR
 * mask stripped. So the name comes from the target and the address from a label, and no
 * mask-stripping is needed anywhere.</p>
 *
 * <p>The fallbacks are what make non-NetBox producers work: generic service discovery puts a real
 * address in the target where NetBox puts a name.</p>
 */
public final class ExporterRenderer {

    /**
     * The label this renderer reads for an exporter's name, which every source emits.
     *
     * <p>Package-private rather than private because it is a contract between the renderer and the
     * sources, not a detail of either: the NetBox plugin emits it, and both sources written here
     * emit it because this is the name that is read.</p>
     */
    static final String NAME_LABEL = "__meta_netbox_name";

    private ExporterRenderer() {
    }

    /**
     * @param groups the parsed document
     * @param addressLabels labels consulted in order for an address, first present wins
     * @param sourceName how the endpoint is named in errors, credentials already redacted
     * @throws IllegalStateException when two entries claim one name, when two entries claim one
     *     address, or when the document yields no entries at all
     */
    public static RenderedExporters render(final List<TargetGroup> groups,
                                           final List<String> addressLabels,
                                           final String sourceName) {
        // NOT sorted here: RenderedExporters normalises into a sorted map as its type invariant,
        // and two copies of one rule meant neither was load-bearing — the test proving the same
        // groups render identically in any order passed with either one deleted (#808). Nothing
        // reads this map in order: it is written, checked for emptiness, and handed over
        final Map<String, String> byName = new LinkedHashMap<>();
        // insertion-ordered so the collision report reads in document order, and every colliding
        // address is listed rather than only the winner
        final Map<String, List<String>> claims = new LinkedHashMap<>();
        // the same map inverted, for the same reason: an address and every distinct name claiming it
        final Map<String, List<String>> addressClaims = new LinkedHashMap<>();
        int skipped = 0;

        for (final TargetGroup group : groups) {
            for (final String target : group.targets()) {
                final String host = host(target);
                final String name = group.labels().getOrDefault(NAME_LABEL, host);
                final String address = address(group, addressLabels, host);
                if (name.isBlank() || address == null || address.isBlank()) {
                    // never guessed at: an exporter entry with the wrong address silently enriches
                    // the wrong device's flows, which is worse than one that enriches nothing. A
                    // blank name is refused the same way: it would be a nameless inventory entry
                    skipped++;
                    continue;
                }
                // deduplicated before the collision check: one device legitimately appearing in two
                // service discovery roles must not wedge every poll. Two entries claiming one name
                // with DIFFERENT addresses still collide below, exactly as before
                final List<String> existingClaims = claims.computeIfAbsent(name, key -> new ArrayList<>());
                if (!existingClaims.contains(address)) {
                    existingClaims.add(address);
                }
                // deduplicated the same way and for the same reason: the repeated device claims its
                // own address twice, which is one claimant, not a collision
                final List<String> existingNames = addressClaims.computeIfAbsent(address, key -> new ArrayList<>());
                if (!existingNames.contains(name)) {
                    existingNames.add(name);
                }
                byName.put(name, address);
            }
        }

        refuseCollisions(claims, sourceName);
        refuseAddressCollisions(addressClaims, sourceName);

        if (byName.isEmpty()) {
            // a successful fetch that yields nothing is the failure mode gnmic users hit, where an
            // empty answer deletes every target. A filter typo or a permission change must not be
            // able to wipe every exporter name
            throw new IllegalStateException(
                    ("%s yielded no exporter entries (%d entr%s skipped for want of a usable address). "
                            + "Keeping the running inventory: a source of truth that answers with nothing "
                            + "is more often a filter or permission mistake than an emptied fleet.")
                            .formatted(sourceName, skipped, skipped == 1 ? "y was" : "ies were"));
        }
        return new RenderedExporters(byName, skipped);
    }

    /**
     * Every colliding name in one report, not the first. NetBox enforces device-name uniqueness per
     * site rather than globally, so two sites each holding a {@code sw1} is ordinary, and being told
     * about one of them at a time costs an operator one poll interval per collision (#630's rule).
     */
    private static void refuseCollisions(final Map<String, List<String>> claims, final String sourceName) {
        final List<String> collisions = collisions(claims);
        if (collisions.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                ("%s returned %d exporter name(s) claimed by more than one entry. Exporter names are "
                        + "inventory keys, so a collision would silently drop every claimant but one. "
                        + "NetBox enforces device-name uniqueness per site, not globally.%n%s")
                        .formatted(sourceName, collisions.size(), String.join(System.lineSeparator(), collisions)));
    }

    /**
     * Every colliding address in one report, for the same reason {@link #refuseCollisions} names
     * every colliding name at once. NetBox enforces uniqueness on device name, not on primary IP, so
     * an HA pair or a virtual-chassis member pair sharing one primary IPv4 is ordinary.
     *
     * <p>Refused here rather than downstream because downstream is worse: the merged document would
     * carry two exporter entries with one address and no observation domain, and
     * {@code PinnedPrefixMatcher.Builder.add} raises its "resolve to the same canonical prefix"
     * refusal in the loader's second pass, outside the {@code Problems} collector — so it propagates
     * out of {@code Inventory.load()} and fails startup, and after boot it is a counted reload
     * failure on every poll, naming one pair at a time.</p>
     *
     * <p>Two entries with the same name AND the same address never reach this: they were
     * deduplicated into one claimant above, because one device can legitimately appear in two
     * service discovery roles. The limit of this check: addresses are compared as they are rendered,
     * not canonicalised, so two spellings of one address ({@code 10.0.0.1} and {@code 10.0.0.1/32})
     * still get as far as the loader's own ambiguity refusal.</p>
     */
    private static void refuseAddressCollisions(final Map<String, List<String>> addressClaims,
                                                final String sourceName) {
        final List<String> collisions = collisions(addressClaims);
        if (collisions.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                ("%s returned %d exporter address(es) claimed by more than one entry. An exporter "
                        + "address is what a flow is matched on, so two entries sharing one with no "
                        + "observation domain are ambiguous and the loader refuses the whole document. "
                        + "NetBox enforces uniqueness on device name, not on primary IP.%n%s")
                        .formatted(sourceName, collisions.size(), String.join(System.lineSeparator(), collisions)));
    }

    /**
     * The claimed key and its claimants, one indented line each, in document order. One copy, shared
     * by both refusals: two copies of this format is the shape that drifts.
     */
    private static List<String> collisions(final Map<String, List<String>> claims) {
        return claims.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "  %s -> %s".formatted(entry.getKey(), String.join(", ", entry.getValue())))
                .toList();
    }

    /**
     * The first address label present, else the target's own host, but only when
     * {@link #isAcceptableAddress} accepts it.
     *
     * <p>The check is what separates the two producers. A generic service discovery target is
     * {@code 10.0.0.5:9100}, whose host is a real address and is used. A NetBox device target is a
     * name, so a device with no primary IP falls through to {@code null} and is skipped and counted,
     * rather than becoming an entry whose address is a hostname the loader would refuse later with
     * a message about the wrong thing. The same check applies to a label's value, not only to the
     * host fallback: one rule, one place, for both sources, so a label reading {@code "not-an-ip"}
     * is a counted skip rather than being used verbatim.</p>
     */
    private static String address(final TargetGroup group, final List<String> addressLabels, final String host) {
        for (final String label : addressLabels) {
            final String value = group.labels().get(label);
            if (value != null && !value.isBlank() && isAcceptableAddress(value)) {
                return value;
            }
        }
        // the generic-producer fallback, gated on the host actually being an address this inventory
        // will accept. A CIDR passes too, and that is deliberate: the exporter matcher is a prefix
        // trie, so a range is a legal entry
        return isAcceptableAddress(host) ? host : null;
    }

    /**
     * Whether a candidate is an address this inventory will accept, decided by the one strict
     * parser the loader itself uses ({@link StrictAddresses}). Not {@code IPAddressString}'s own
     * validator: its defaults accept inet_aton and single-segment spellings, so a device named
     * "1234" would render as the address 0.0.4.210 instead of being skipped.
     */
    private static boolean isAcceptableAddress(final String candidate) {
        try {
            StrictAddresses.parse(candidate, false);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * A target's host: brackets and any trailing port removed.
     *
     * <p>{@code [2001:db8::2]:9100} and {@code 10.0.0.1:9100} both lose their port, while a bare
     * {@code 2001:db8::2} keeps every colon it has. The bracket form is what disambiguates the two,
     * which is exactly why the Prometheus contract uses it.</p>
     */
    private static String host(final String target) {
        final String trimmed = target.trim();
        if (trimmed.startsWith("[")) {
            final int close = trimmed.indexOf(']');
            return close < 0 ? trimmed : trimmed.substring(1, close);
        }
        final int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return trimmed;
        }
        // more than one colon and no brackets: a bare IPv6 literal, where no part is a port
        return trimmed.indexOf(':', colon + 1) >= 0 ? trimmed : trimmed.substring(0, colon);
    }
}
