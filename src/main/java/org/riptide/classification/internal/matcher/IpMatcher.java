/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import java.util.Objects;
import java.util.function.Function;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.IpAddr;
import org.riptide.classification.internal.value.IpValue;

class IpMatcher implements Matcher {

    private final Function<ClassificationRequest, IpAddr> valueExtractor;

    private final IpValue value;

    protected IpMatcher(final IpValue input,
                        final Function<ClassificationRequest, IpAddr> valueExtractor) {
        this.value = Objects.requireNonNull(input);
        this.valueExtractor = Objects.requireNonNull(valueExtractor);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The extractor yields an {@link IpAddr}, so this takes {@link IpValue#isInRange(IpAddr)} and never
     * the {@code String} overload with its {@code Objects.requireNonNull}. Without the guard the null
     * reached {@code IpRange.contains}, which calls {@code begin.compareTo(addr)}; {@code compareTo}
     * casts its argument and reads that argument's primitive field, so it is a plain null dereference of
     * the address passed in. The same holds for {@code Ip6Addr}.
     * <p>
     * No bundled rule names an address, so nothing reaches this with a null there. It is still on the
     * live path: a v9 or IPFIX template need not carry an address at all, which is why
     * {@link IpAddr#of(java.net.InetAddress)} answers null rather than throwing.
     */
    @Override
    public boolean matches(ClassificationRequest request) {
        final IpAddr address = valueExtractor.apply(request);
        return address != null && value.isInRange(address);
    }
}
