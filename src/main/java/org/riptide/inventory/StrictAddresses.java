/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import java.util.Optional;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import inet.ipaddr.IPAddressStringParameters;

/**
 * The one strict address parser (#538). Shared by the inventory loader and the legacy
 * converter, which used to carry independent copies of the parameters — the same
 * copy-drift setup that has already produced real divergence twice elsewhere.
 *
 * <p>Strictness posture: a typo must fail, not quietly mean a different address. The
 * sharpest instance is the netmask spelling: under the previous parameters,
 * {@code 10.90.0.0/255.0.255.0} (non-contiguous) was silently accepted as host
 * {@code 10.0.0.0} — an address the operator never wrote — and, being a single host,
 * sailed past the v1/v2c width rule. {@code allowMask(false)} rejects the whole netmask
 * class, contiguous forms and IPv6 mask spellings included: one accepted spelling per
 * meaning is the posture, and two spellings for one thing is exactly the ambiguity that
 * hid the trap.</p>
 *
 * <p>On failure, a diagnosis ladder re-parses with one rule relaxed at a time and names
 * the rule that fired plus a suggestion that is guaranteed to round-trip through this
 * parser (pinned by test): the message never hands the operator a string it would itself
 * reject.</p>
 *
 * <p>Known acceptances beyond the letter of the rules, deliberate and documented rather
 * than implied away: surrounding whitespace is trimmed by the library, and IPv6 mixed
 * notation ({@code ::ffff:1.2.3.4}) parses. Single-segment forms (a lone {@code 0},
 * base85) are rejected. A leading zero in the prefix length ({@code /016}) is accepted:
 * the library's flag for it also rejects the legitimate {@code /0} spellings.</p>
 */
public final class StrictAddresses {

    private static final IPAddressStringParameters STRICT = strictBuilder()
            .allowMask(false)
            .toParams();

    /** One rule relaxed per ladder arm; everything else stays strict. */
    private static final IPAddressStringParameters MASKS_ALLOWED = strictBuilder()
            .toParams();

    private static final IPAddressStringParameters ZEROS_ALLOWED = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allowSingleSegment(false)
            .allow_inet_aton(false)
            .allowMask(false)
            .toParams();

    private static final IPAddressStringParameters ATON_ALLOWED = strictBuilder()
            .allowMask(false)
            .allow_inet_aton(true)
            .toParams();

    /**
     * Masks and leading zeros relaxed together, for spellings that combine both. inet_aton
     * is deliberately NOT part of this arm: relaxing it alongside zeros makes {@code 010}
     * read as octal 8, and the arm would suggest {@code 8.0.0.0/8} for an operator who
     * almost certainly meant decimal 10 — the exact silent reinterpretation this class
     * refuses. A spelling combining inet_aton with the others falls to the generic
     * message instead of a wrong suggestion.
     */
    private static final IPAddressStringParameters MASKS_AND_ZEROS_ALLOWED = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allowSingleSegment(false)
            .allow_inet_aton(false)
            .toParams();

    private static IPAddressStringParameters.Builder strictBuilder() {
        final IPAddressStringParameters.Builder builder = new IPAddressStringParameters.Builder()
                .allowEmpty(false)
                .allowAll(false)
                .allowSingleSegment(false)
                .allow_inet_aton(false);
        // allowPrefixLengthLeadingZeros(false) is deliberately NOT set: the library
        // treats the lone 0 in /0 as a leading zero, so the flag rejects the legitimate
        // any-width spellings 0.0.0.0/0 and ::/0 (probe-verified). /016 therefore stays
        // accepted — a documented quirk, chosen over breaking /0
        builder.getIPv4AddressParametersBuilder().allowLeadingZeros(false);
        builder.getIPv6AddressParametersBuilder().allowLeadingZeros(false);
        return builder;
    }

    private StrictAddresses() {
    }

    /**
     * Parses {@code value} as a host address or (unless {@code hostOnly}) a single CIDR
     * prefix block.
     *
     * @throws IllegalArgumentException whose message is a diagnosis clause completing the
     *         sentence "The agent range 'X' …" — it names the rule that fired and, where
     *         one exists, a fix that this parser itself accepts
     */
    public static IPAddressString parse(final String value, final boolean hostOnly) {
        final IPAddressString parsed = new IPAddressString(value, STRICT);
        if (acceptable(parsed.getAddress(), hostOnly)) {
            return parsed;
        }
        throw new IllegalArgumentException(diagnose(value, hostOnly, parsed.getAddress()));
    }

    /**
     * The CIDR form of a contiguous whole-block netmask spelling, for the legacy
     * converter: 0.8 accepted {@code 10.90.0.0/255.255.0.0} and the converter's charter is
     * a mechanical rewrite, so it translates this spelling rather than refusing it. Empty
     * for anything else — a non-contiguous mask has no CIDR equivalent, and a mask that
     * keeps host bits was rejected by 0.8's own host-or-block rule too.
     */
    public static Optional<String> cidrFormOfContiguousNetmask(final String value) {
        if (new IPAddressString(value, STRICT).getAddress() != null) {
            return Optional.empty();
        }
        final IPAddress masked = new IPAddressString(value, MASKS_ALLOWED).getAddress();
        return masked != null && masked.isPrefixed() && masked.isSinglePrefixBlock()
                ? Optional.of(masked.toCanonicalString())
                : Optional.empty();
    }

    private static boolean acceptable(final IPAddress address, final boolean hostOnly) {
        if (address == null) {
            return false;
        }
        final boolean host = !address.isMultiple() && !address.isPrefixed();
        final boolean block = address.isPrefixed() && address.isSinglePrefixBlock();
        return hostOnly ? host : (host || block);
    }

    /**
     * The ladder: one relaxed re-parse per arm, in the order an operator is most likely to
     * hit them; a combined arm catches spellings mixing several rejected forms; nonsense
     * keeps the original generic message. Every suggested string round-trips through
     * {@link #parse}, pinned by test — an error that hands the operator a string the same
     * parser then rejects is worse than a generic one.
     */
    private static String diagnose(final String value, final boolean hostOnly, final IPAddress wrongShape) {
        if (wrongShape != null) {
            return shapeClause(wrongShape, hostOnly);
        }

        final IPAddress masked = new IPAddressString(value, MASKS_ALLOWED).getAddress();
        if (masked != null) {
            // contiguity of the MASK is what isPrefixed() reports: a contiguous mask
            // yields a prefixed result (host bits kept or not), a non-contiguous one
            // yields a bare masked host. The first version keyed on isSinglePrefixBlock
            // and called a contiguous mask with host bits "non-contiguous" — every clause
            // of that message was false
            if (!masked.isPrefixed()) {
                // the trap this class exists for: the mask zeroes bits the operator
                // wrote, and naming the collapsed address is what makes it convincing
                return ("uses a non-contiguous netmask, which would mean an address you never wrote "
                        + "(%s); it has no CIDR equivalent.").formatted(masked.toCanonicalString());
            }
            if (masked.isSinglePrefixBlock() && !hostOnly) {
                return "uses a netmask; write the CIDR form '%s'.".formatted(masked.toCanonicalString());
            }
            return ("uses a netmask and keeps host bits; write '%s' (the covered block) or '%s' "
                    + "(the single host).").formatted(
                    masked.toPrefixBlock().toCanonicalString(),
                    masked.withoutPrefixLength().toCanonicalString());
        }

        final IPAddress zeros = new IPAddressString(value, ZEROS_ALLOWED).getAddress();
        if (zeros != null) {
            return ("has leading zeros; write '%s' (some tools read leading zeros as octal — "
                    + "confirm that is the address you meant).").formatted(suggestion(zeros, hostOnly));
        }

        final IPAddress aton = new IPAddressString(value, ATON_ALLOWED).getAddress();
        if (aton != null) {
            return "is inet_aton shorthand; write all four octets ('%s').".formatted(suggestion(aton, hostOnly));
        }

        final IPAddress combined = new IPAddressString(value, MASKS_AND_ZEROS_ALLOWED).getAddress();
        if (combined != null) {
            return "combines several rejected spellings; write '%s'.".formatted(suggestion(combined, hostOnly));
        }

        return generic(hostOnly);
    }

    /** Wrong shape after a clean parse: name the shape rule, and the fix where one exists. */
    private static String shapeClause(final IPAddress address, final boolean hostOnly) {
        if (hostOnly) {
            return "is not a single host address.";
        }
        if (address.isPrefixed() && !address.isSinglePrefixBlock()) {
            return ("has host bits set for its /%d prefix; write '%s' (the covered block) or '%s' "
                    + "(the single host).").formatted(
                    address.getNetworkPrefixLength(),
                    address.toPrefixBlock().toCanonicalString(),
                    address.withoutPrefixLength().toCanonicalString());
        }
        if (address.isMultiple()) {
            final IPAddress block = address.assignPrefixForSingleBlock();
            if (block != null) {
                return "is a wildcard spelling; write the CIDR form '%s'.".formatted(block.toCanonicalString());
            }
        }
        return generic(false);
    }

    /**
     * A suggestion guaranteed to satisfy the shape rule: the arm's canonical form when it
     * already does, else the nearest form that does. Without this, "write '10.0.0.5/24'"
     * (leading zeros fixed, host bits still set) sent the operator into error ping-pong.
     */
    private static String suggestion(final IPAddress address, final boolean hostOnly) {
        if (acceptable(address, hostOnly)) {
            return address.toCanonicalString();
        }
        if (address.isPrefixed() && !address.isSinglePrefixBlock() && !hostOnly) {
            return address.toPrefixBlock().toCanonicalString();
        }
        if (address.isPrefixed() && hostOnly) {
            return address.withoutPrefixLength().toCanonicalString();
        }
        if (address.isMultiple() && !hostOnly) {
            final IPAddress block = address.assignPrefixForSingleBlock();
            if (block != null) {
                return block.toCanonicalString();
            }
        }
        return address.toCanonicalString();
    }

    private static String generic(final boolean hostOnly) {
        return hostOnly ? "is not a single host address." : "is not a host address or CIDR prefix.";
    }
}
