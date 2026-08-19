/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.utils;

import java.util.Arrays;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The property-name walk, once. Six call sites used to repeat the same narrowing and
 * iteration — three startup checks and three scans inside the config reloader — differing
 * only in what they did with the names (first match, any match, every match, or the values
 * behind them). That is policy, and it stays with the callers; this holds the part they
 * genuinely shared.
 *
 * <p><b>Non-enumerable sources are invisible here</b>, and that is the semantic worth
 * stating once instead of implying it six times. A {@link PropertySource} that cannot
 * enumerate its names contributes nothing, so a custom source carrying a key one of the
 * fatal startup checks looks for would not be seen and would not fail the boot. Every
 * source Spring itself contributes to the environment — system properties, environment
 * variables, YAML documents, command-line arguments — is enumerable, so this is a property
 * of hypothetical custom sources rather than a live hole. Fixing it would mean deciding
 * what a check should do with a source whose contents it cannot read, which is a different
 * question from where the loop lives.</p>
 */
public final class PropertySources {

    private PropertySources() {
    }

    /**
     * The property names of one source, or nothing when it cannot enumerate them.
     *
     * <p>Callers that read values as well as names want this overload rather than
     * {@link #propertyNames(Iterable)}: the owning source has to stay in scope for
     * {@code source.getProperty(name)} to mean anything.</p>
     */
    public static Stream<String> propertyNames(final PropertySource<?> source) {
        return source instanceof EnumerablePropertySource<?> enumerable
                ? Arrays.stream(enumerable.getPropertyNames())
                : Stream.empty();
    }

    /**
     * The property names of every source in the stack, in stack order.
     *
     * <p>Lazy, so a short-circuiting terminal such as {@code findFirst} or {@code anyMatch}
     * stops at the first hit without enumerating the sources behind it — the same
     * early-return behaviour the hand-written loops had.</p>
     */
    public static Stream<String> propertyNames(final Iterable<PropertySource<?>> sources) {
        return StreamSupport.stream(sources.spliterator(), false)
                .flatMap(PropertySources::propertyNames);
    }
}
