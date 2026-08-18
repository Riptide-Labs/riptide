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
 * hid the trap. Zone ids ({@code fe80::1%eth0}) are rejected for the inverse reason
 * (#553, probe-verified): matching ignores zones — a zoned entry matches flows from ANY
 * interface, numeric-scope and foreign-zone probes included — so the spelling promises
 * an interface constraint that nothing enforces.</p>
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

    private static final IPAddressStringParameters ZEROS_ALLOWED = zerosAllowedParams();

    private static IPAddressStringParameters zerosAllowedParams() {
        final IPAddressStringParameters.Builder builder = baseBuilder().allowMask(false);
        // one relaxation per arm: zones stay banned here, chosen explicitly rather than
        // inherited from the builder default, so a zoned spelling never gets a
        // leading-zeros diagnosis
        builder.getIPv6AddressParametersBuilder().allowZone(false);
        return builder.toParams();
    }

    private static final IPAddressStringParameters ATON_ALLOWED = strictBuilder()
            .allowMask(false)
            .allow_inet_aton(true)
            .toParams();

    /**
     * One rule relaxed: the zone. Masks stay banned, so a zoned netmask spelling falls
     * to the combined arm rather than getting a half-right zone-only message.
     */
    private static final IPAddressStringParameters ZONES_ALLOWED = zoneTolerantBuilder()
            .allowMask(false)
            .toParams();

    /**
     * Masks and leading zeros relaxed together, for spellings that combine both. inet_aton
     * is deliberately NOT part of this arm: relaxing it alongside zeros makes {@code 010}
     * read as octal 8, and the arm would suggest {@code 8.0.0.0/8} for an operator who
     * almost certainly meant decimal 10 — the exact silent reinterpretation this class
     * refuses. A spelling combining inet_aton with the others falls to the generic
     * message instead of a wrong suggestion.
     */
    private static final IPAddressStringParameters MASKS_AND_ZEROS_ALLOWED = masksAndZerosParams();

    private static IPAddressStringParameters masksAndZerosParams() {
        final IPAddressStringParameters.Builder builder = baseBuilder();
        // zones explicitly allowed (the builder default, chosen rather than inherited):
        // this is the catch-all for spellings combining several rejected forms, zone
        // included — and the arm de-zones before suggesting, so the fix still parses
        builder.getIPv6AddressParametersBuilder().allowZone(true);
        return builder.toParams();
    }

    /**
     * The four base rules every param set shares, stated once: this class exists because
     * parameter copies drift (see the class javadoc), and the ladder's arm builders were
     * about to become the third copy of this list.
     */
    private static IPAddressStringParameters.Builder baseBuilder() {
        return new IPAddressStringParameters.Builder()
                .allowEmpty(false)
                .allowAll(false)
                .allowSingleSegment(false)
                .allow_inet_aton(false);
    }

    private static IPAddressStringParameters.Builder strictBuilder() {
        final IPAddressStringParameters.Builder builder = zoneTolerantBuilder();
        // zone ids are rejected because matching ignores them (#553, probe-verified):
        // fe80::1%eth0 reads as "this address on eth0" but would match a flow from ANY
        // interface — the spelling promises a constraint nothing enforces, and the
        // ambiguity guard already treats zoned and unzoned as the same canonical prefix
        builder.getIPv6AddressParametersBuilder().allowZone(false);
        return builder;
    }

    /** {@link #strictBuilder()} minus the zone ban: the base for the zone ladder arm. */
    private static IPAddressStringParameters.Builder zoneTolerantBuilder() {
        final IPAddressStringParameters.Builder builder = baseBuilder();
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

    /**
     * The zone-stripped form of a zoned spelling, for the legacy converter: 0.8 accepted
     * zones and its matcher was equally zone-blind (#553), so the converter translates
     * the spelling rather than refusing it — meaning preserved, which is its charter.
     * Empty for anything else, including a zoned NETMASK spelling: that combines two
     * translations, and it is refused with the combined-arm diagnosis instead of
     * translated in a silently compound step.
     */
    public static Optional<String> zoneStrippedForm(final String value) {
        if (new IPAddressString(value, STRICT).getAddress() != null) {
            return Optional.empty();
        }
        final IPAddress zoned = new IPAddressString(value, ZONES_ALLOWED).getAddress();
        if (zoned == null) {
            return Optional.empty();
        }
        final IPAddress stripped = dezoned(zoned);
        // shape-guarded like the netmask sibling: a zoned spelling whose STRIPPED form
        // still violates a shape rule (host bits, ranges) is not translated — the
        // converter would otherwise fail naming a spelling that is not in the operator's
        // file, with the summary line explaining the rewrite discarded by the throw.
        // Left untranslated, the refusal diagnoses the ORIGINAL through the zone arm
        return acceptable(stripped, false)
                ? Optional.of(stripped.toCanonicalString())
                : Optional.empty();
    }

    /** The zone-stripped address, spelled once for the three sites that need it. */
    private static IPAddress dezoned(final IPAddress address) {
        return address.isIPv6() && address.toIPv6().hasZone()
                ? address.toIPv6().removeZone()
                : address;
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
            if (hostOnly) {
                // a netmask can only spell a block or a range, which a host-only caller
                // never accepts — the "keeps host bits" clause below would be false for a
                // whole-block mask, and its block suggestion rejected by the same caller
                return ("uses a netmask where only a single host address is allowed; write a plain "
                        + "host (e.g. '%s').").formatted(
                        masked.getLower().withoutPrefixLength().toCanonicalString());
            }
            if (masked.isSinglePrefixBlock()) {
                return "uses a netmask; write the CIDR form '%s'.".formatted(masked.toCanonicalString());
            }
            // guarded like every other suggestion: a mask combined with a RANGED address
            // part (10.1-5.0.0/255.255.0.0) derives a block that is no single block and a
            // "host" that is no host — both un-parseable, so the generic message is honest
            // guarded like every other suggestion: a mask combined with a RANGED address
            // part (10.1-5.0.0/255.255.0.0) derives a block that is no single block and a
            // "host" that is no host — both un-parseable, so the generic message is honest
            final IPAddress coveredBlock = masked.toPrefixBlock();
            final IPAddress singleHost = masked.withoutPrefixLength();
            if (acceptable(coveredBlock, false) && acceptable(singleHost, true)) {
                return ("uses a netmask and keeps host bits; write '%s' (the covered block) or '%s' "
                        + "(the single host).").formatted(
                        coveredBlock.toCanonicalString(), singleHost.toCanonicalString());
            }
            return generic(false);
        }

        final IPAddress zoned = new IPAddressString(value, ZONES_ALLOWED).getAddress();
        if (zoned != null) {
            // guaranteed IPv6: ZONES_ALLOWED differs from STRICT only in the v6 zone, so
            // an address parsing here and not there carries a zone MARKER — though not
            // necessarily a zone: a bare trailing '%' parses with getZone() == null
            // (probe-verified), so the shortcut "parses here implies has a zone" is
            // false. The zone is UNQUOTED in the message deliberately: every
            // single-quoted string in a diagnosis must round-trip through this parser
            // (pinned by test), and '%eth0' never would
            final IPAddress stripped = dezoned(zoned);
            final String zone = zoned.toIPv6().getZone();
            final String marker = zone == null ? "a dangling zone marker (%)" : "a zone id (%" + zone + ")";
            final String fix = suggestion(stripped, hostOnly);
            if (fix == null) {
                // still name the zone rule: falling to the bare generic made the operator
                // discover it only on a second failed submission (error ping-pong)
                return "has %s and %s".formatted(marker, generic(hostOnly));
            }
            if (zone == null) {
                // a dangling marker has no zone for matching to ignore — name it, not "%null"
                return "has %s; write '%s'.".formatted(marker, fix);
            }
            if (acceptable(stripped, hostOnly)) {
                return ("has %s; matching ignores zones, so the entry would silently "
                        + "mean '%s' from any interface; write '%s'.").formatted(marker, fix, fix);
            }
            // the stripped form itself violates a shape rule too; claiming the entry
            // "would mean" the derived fix overstates — name the zone and the fix only
            return "has %s; matching ignores zones; write '%s'.".formatted(marker, fix);
        }

        final IPAddress zeros = new IPAddressString(value, ZEROS_ALLOWED).getAddress();
        if (zeros != null) {
            final String fix = suggestion(zeros, hostOnly);
            return fix == null ? generic(hostOnly)
                    : ("has leading zeros; write '%s' (some tools read leading zeros as octal — "
                    + "confirm that is the address you meant).").formatted(fix);
        }

        final IPAddress aton = new IPAddressString(value, ATON_ALLOWED).getAddress();
        if (aton != null) {
            final String fix = suggestion(aton, hostOnly);
            return fix == null ? generic(hostOnly)
                    : "is inet_aton shorthand; write all four octets ('%s').".formatted(fix);
        }

        final IPAddress combined = new IPAddressString(value, MASKS_AND_ZEROS_ALLOWED).getAddress();
        if (combined != null) {
            // de-zone before suggesting: a zoned combined spelling would otherwise have
            // its zoned canonical form suggested — a string this same parser rejects
            final String fix = suggestion(dezoned(combined), hostOnly);
            return fix == null ? generic(hostOnly)
                    : "combines several rejected spellings; write '%s'.".formatted(fix);
        }

        return generic(hostOnly);
    }

    /** Wrong shape after a clean parse: name the shape rule, and the fix where one exists. */
    private static String shapeClause(final IPAddress address, final boolean hostOnly) {
        if (hostOnly) {
            return "is not a single host address.";
        }
        if (address.isPrefixed() && !address.isSinglePrefixBlock()) {
            // same guard as the netmask arm: a ranged address part (10.0.1-5.5/24)
            // derives two un-parseable "fixes", and the generic message beats both
            // same guard as the netmask arm: a ranged address part (10.0.1-5.5/24)
            // derives two un-parseable "fixes", and the generic message beats both
            final IPAddress coveredBlock = address.toPrefixBlock();
            final IPAddress singleHost = address.withoutPrefixLength();
            if (acceptable(coveredBlock, false) && acceptable(singleHost, true)) {
                return ("has host bits set for its /%d prefix; write '%s' (the covered block) or '%s' "
                        + "(the single host).").formatted(
                        address.getNetworkPrefixLength(),
                        coveredBlock.toCanonicalString(), singleHost.toCanonicalString());
            }
            return generic(false);
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
     * already does, else the nearest form that does, else {@code null} — and the arms fall
     * back to the generic message on null. Without the guarantee, "write '10.0.0.5/24'"
     * (leading zeros fixed, host bits still set) sent the operator into error ping-pong;
     * without the null fallback, a relaxed multi-address covering no single block (e.g.
     * {@code 010.0.*.0}) had its canonical form suggested — a string this same parser
     * rejects. The derived candidate is re-checked against the shape rule, never assumed.
     */
    private static String suggestion(final IPAddress address, final boolean hostOnly) {
        if (acceptable(address, hostOnly)) {
            return address.toCanonicalString();
        }
        final IPAddress derived;
        if (address.isPrefixed() && !hostOnly) {
            derived = address.toPrefixBlock();
        } else if (address.isPrefixed() && !address.isMultiple()) {
            derived = address.withoutPrefixLength();
        } else if (address.isMultiple() && !hostOnly) {
            derived = address.assignPrefixForSingleBlock();
        } else {
            derived = null;
        }
        return derived != null && acceptable(derived, hostOnly) ? derived.toCanonicalString() : null;
    }

    private static String generic(final boolean hostOnly) {
        return hostOnly ? "is not a single host address." : "is not a host address or CIDR prefix.";
    }
}
