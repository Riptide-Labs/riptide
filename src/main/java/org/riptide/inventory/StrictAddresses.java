/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

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
 * class, contiguous forms included: one accepted spelling per meaning is the posture, and
 * two spellings for one thing is exactly the ambiguity that hid the trap.</p>
 *
 * <p>On failure, a diagnosis ladder re-parses with exactly one rule relaxed and names the
 * rule that fired plus the concrete fix — the lenient parse supplies the canonical
 * suggestion for free.</p>
 */
public final class StrictAddresses {

    /**
     * No netmasks, no inet_aton joins, no leading zeros, no empty or all-address forms.
     */
    public static final IPAddressStringParameters STRICT = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allow_inet_aton(false)
            .allowMask(false)
            .getIPv4AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .getIPv6AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .toParams();

    /** One rule relaxed per ladder arm; everything else stays strict. */
    private static final IPAddressStringParameters MASKS_ALLOWED = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allow_inet_aton(false)
            .getIPv4AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .getIPv6AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .toParams();

    private static final IPAddressStringParameters ZEROS_ALLOWED = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allow_inet_aton(false)
            .allowMask(false)
            .toParams();

    private static final IPAddressStringParameters ATON_ALLOWED = new IPAddressStringParameters.Builder()
            .allowEmpty(false)
            .allowAll(false)
            .allowMask(false)
            .getIPv4AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .getIPv6AddressParametersBuilder().allowLeadingZeros(false).getParentBuilder()
            .toParams();

    private StrictAddresses() {
    }

    /**
     * Parses {@code value} as a host address or (unless {@code hostOnly}) a single CIDR
     * prefix block.
     *
     * @throws IllegalArgumentException whose message is a diagnosis clause completing the
     *         sentence "The agent range 'X' …" — it names the rule that fired and the
     *         concrete fix, never just "invalid"
     */
    public static IPAddressString parse(final String value, final boolean hostOnly) {
        final IPAddressString parsed = new IPAddressString(value, STRICT);
        final IPAddress address = parsed.getAddress();
        final boolean host = address != null && !address.isMultiple() && !address.isPrefixed();
        final boolean block = address != null && address.isPrefixed() && address.isSinglePrefixBlock();
        if (hostOnly ? host : (host || block)) {
            return parsed;
        }
        throw new IllegalArgumentException(diagnose(value, hostOnly, address != null));
    }

    /**
     * The ladder: one relaxed re-parse per arm, in the order an operator is most likely to
     * hit them. Each suggestion is the lenient parse's canonical form, so the message
     * carries the exact string to write.
     */
    private static String diagnose(final String value, final boolean hostOnly, final boolean parsedButWrongShape) {
        if (parsedButWrongShape) {
            // strict parse succeeded and the shape is wrong (host bits set with a prefix,
            // or a range where only a host is allowed): the original messages, unchanged
            return hostOnly ? "is not a single host address." : "is not a host address or CIDR prefix.";
        }

        final IPAddress masked = new IPAddressString(value, MASKS_ALLOWED).getAddress();
        if (masked != null) {
            if (masked.isPrefixed() && masked.isSinglePrefixBlock()) {
                return ("uses a netmask; write the CIDR form '%s'.").formatted(masked);
            }
            // the trap this class exists for: the mask zeroes bits the operator wrote,
            // and naming the collapsed address is what makes the refusal convincing
            return ("uses a non-contiguous netmask, which would mean an address you never wrote "
                    + "(%s); it has no CIDR equivalent.").formatted(masked.toCanonicalString());
        }

        final IPAddress zeros = new IPAddressString(value, ZEROS_ALLOWED).getAddress();
        if (zeros != null) {
            return "has leading zeros; write '%s'.".formatted(zeros.toCanonicalString());
        }

        final IPAddress aton = new IPAddressString(value, ATON_ALLOWED).getAddress();
        if (aton != null) {
            return "is inet_aton shorthand; write all four octets ('%s').".formatted(aton.toCanonicalString());
        }

        return hostOnly ? "is not a single host address." : "is not a host address or CIDR prefix.";
    }
}
