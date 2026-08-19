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
 * <p>Named {@code PropertyNames} rather than {@code PropertySources} deliberately: Spring's
 * own {@link org.springframework.core.env.PropertySources} is the interface
 * {@code MutablePropertySources} implements, and shadowing it in a codebase that passes
 * those around would trip the next person who imports both.</p>
 *
 * <p><b>Sources that cannot enumerate their names are skipped, and that is required rather
 * than regrettable.</b> Spring Boot contributes two such sources to every environment
 * (verified on this project's Spring Boot 4.1.0 by dumping a booted context):</p>
 * <ul>
 *   <li>{@code configurationProperties} — a <em>view over the whole rest of the stack</em>.
 *       Visiting it as well would count every key twice, and the callers that attribute a
 *       value to its owning document would attribute it to a source that owns nothing.</li>
 *   <li>{@code random} — {@code random.int}, {@code random.uuid}, {@code random.int(1,10)}:
 *       an unbounded namespace that cannot be listed by construction.</li>
 * </ul>
 *
 * <p>So the skip is load-bearing. In particular, do not "repair" it by falling back to
 * {@code source.getProperty(name)} for non-enumerable sources: {@code configurationProperties}
 * resolves <em>any</em> name from the entire stack, so a probe built that way would answer
 * yes for every key the startup checks look for. The residual risk is narrow and
 * hypothetical — a custom source that both carries a key a fatal check looks for and cannot
 * list its names — and closing it would mean deciding what a check should do about contents
 * it cannot read, which is a different question from where the loop lives.</p>
 */
public final class PropertyNames {

    private PropertyNames() {
    }

    /**
     * The property names of one source, or nothing when it cannot enumerate them.
     *
     * <p>Callers that read values as well as names want this overload rather than
     * {@link #in(Iterable)}: the owning source has to stay in scope for
     * {@code source.getProperty(name)} to mean anything.</p>
     */
    public static Stream<String> in(final PropertySource<?> source) {
        return source instanceof EnumerablePropertySource<?> enumerable
                ? Arrays.stream(enumerable.getPropertyNames())
                : Stream.empty();
    }

    /**
     * The property names of every enumerable source in the stack, in stack order.
     *
     * <p>Lazy, so a short-circuiting terminal such as {@code findFirst} or {@code anyMatch}
     * stops at the first hit without enumerating the sources behind it — the same
     * early-return behaviour the hand-written loops had.</p>
     */
    public static Stream<String> in(final Iterable<PropertySource<?>> sources) {
        return StreamSupport.stream(sources.spliterator(), false)
                .flatMap(PropertyNames::in);
    }
}
