/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.utils;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared property-name walk (#560). Its two contracts — non-enumerable sources
 * contribute nothing, and the stack is walked lazily — are invisible to the callers'
 * tests, which is why they are pinned here.
 */
class PropertyNamesTest {

    private static MapPropertySource source(final String name, final String... keys) {
        final Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (final String key : keys) {
            values.put(key, "value-of-" + key);
        }
        return new MapPropertySource(name, values);
    }

    @Test
    void anEnumerableSourceYieldsItsNamesInOrder() {
        assertThat(PropertyNames.in(source("one", "a", "b", "c")))
                .containsExactly("a", "b", "c");
    }

    /**
     * The skip is load-bearing, not a latent hole. Spring Boot contributes non-enumerable
     * sources to every environment, and {@code configurationProperties} is the sharp one:
     * it is a view over the whole rest of the stack, so visiting it would double-count
     * every key and misattribute ownership. Its class is package-private, so this pins the
     * other one structurally — the review that produced this test found the javadoc had
     * claimed the opposite ("every source Spring itself contributes is enumerable"), which
     * would have invited a "fix" that breaks the callers.
     */
    @Test
    void springBootContributesNonEnumerableSources() {
        assertThat(EnumerablePropertySource.class
                .isAssignableFrom(org.springframework.boot.env.RandomValuePropertySource.class))
                .as("RandomValuePropertySource cannot enumerate random.int/random.uuid/...")
                .isFalse();
    }

    /**
     * The documented semantic: a source that cannot enumerate its names contributes
     * nothing, so a fatal-key check would not see a key hiding in one.
     */
    @Test
    void aNonEnumerableSourceYieldsNothing() {
        final PropertySource<?> opaque = new PropertySource<Object>("opaque", new Object()) {
            @Override
            public Object getProperty(final String name) {
                return "riptide.nodes.hidden";
            }
        };

        assertThat(PropertyNames.in(opaque)).isEmpty();
    }

    @Test
    void theStackOverloadFlattensInStackOrderAndSkipsNonEnumerableSources() {
        final PropertySource<?> opaque = new PropertySource<Object>("opaque", new Object()) {
            @Override
            public Object getProperty(final String name) {
                return null;
            }
        };
        final List<PropertySource<?>> stack = List.of(source("first", "a"), opaque, source("second", "b"));

        assertThat(PropertyNames.in(stack)).containsExactly("a", "b");
    }

    /**
     * Laziness is the behaviour the hand-written loops had via early {@code return}, and
     * losing it would be invisible at every call site: the results would still be right,
     * only the work wasted. The exploding source is never reached if the walk short-circuits.
     */
    @Test
    void aShortCircuitingTerminalNeverTouchesLaterSources() {
        final EnumerablePropertySource<?> explodes = new EnumerablePropertySource<Object>("explodes", new Object()) {
            @Override
            public String[] getPropertyNames() {
                throw new AssertionError("later sources must not be enumerated after a short circuit");
            }

            @Override
            public Object getProperty(final String name) {
                return null;
            }
        };
        final List<PropertySource<?>> stack = List.of(source("first", "riptide.nodes.core"), explodes);

        assertThat(PropertyNames.in(stack).filter(name -> name.startsWith("riptide.nodes")).findFirst())
                .contains("riptide.nodes.core");
        assertThat(PropertyNames.in(stack).anyMatch(name -> name.startsWith("riptide.nodes"))).isTrue();

        // and the guard itself is honest: a terminal that must see everything does explode
        assertThatThrownBy(() -> PropertyNames.in(stack).count())
                .isInstanceOf(AssertionError.class);
    }
}
