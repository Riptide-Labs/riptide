/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole path language, at every edge.
 *
 * <p>It is one sentence of behaviour, so each edge is one test rather than a paragraph of
 * documentation: what a missing field does, what a null does, what a container where a value was
 * wanted does, and what a path that is not a path does. A language this small is only small if its
 * edges are decided rather than discovered (#800).</p>
 */
class JsonPathTest {

    private static final String KEY = "riptide.discovery.mapping.address";

    private static JsonNode json(final String text) {
        try {
            return new ObjectMapper().readTree(text);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aPathWalksFieldsAndReturnsTheValue() {
        assertThat(JsonPath.of("a.b.c", KEY).read(json("""
                {"a": {"b": {"c": "found"}}}
                """))).contains("found");
    }

    @Test
    void aMissingFieldAnywhereIsEmptyRatherThanAFailure() {
        final JsonNode device = json("""
                {"a": {"b": {"c": "found"}}}
                """);

        assertThat(JsonPath.of("a.x.c", KEY).read(device))
                .as("a device this mapping does not describe is skipped downstream, not refused")
                .isEmpty();
        assertThat(JsonPath.of("nope", KEY).read(device)).isEmpty();
    }

    @Test
    void aNullIsEmpty() {
        assertThat(JsonPath.of("ip", KEY).read(json("""
                {"ip": null}
                """))).isEmpty();
    }

    @Test
    void anObjectOrArrayWhereAValueWasWantedIsEmpty() {
        assertThat(JsonPath.of("a", KEY).read(json("""
                {"a": {"b": 1}}
                """)))
                .as("rendering one would produce an exporter named '{b=1}'")
                .isEmpty();
        assertThat(JsonPath.of("a", KEY).read(json("""
                {"a": [1, 2]}
                """))).isEmpty();
    }

    @Test
    void aNumberOrBooleanIsReadAsItsText() {
        assertThat(JsonPath.of("id", KEY).read(json("""
                {"id": 42}
                """)))
                .as("an endpoint that numbers its devices still names them")
                .contains("42");
    }

    @Test
    void walkingThroughAValueRatherThanAnObjectIsEmpty() {
        assertThat(JsonPath.of("a.b", KEY).read(json("""
                {"a": "not an object"}
                """))).isEmpty();
    }

    /**
     * A dot is a separator and nothing else. There is no escape, and the javadoc says so: a quoting
     * rule bolted onto a one-sentence language is how the language stops being one sentence.
     */
    @Test
    void aFieldNameContainingADotIsNotReachable() {
        assertThat(JsonPath.of("a.b", KEY).read(json("""
                {"a.b": "unreachable"}
                """)))
                .as("the path means field 'a' then field 'b', which this object does not have")
                .isEmpty();
    }

    @Test
    void aBlankPathIsRefusedNamingTheKey() {
        assertThatThrownBy(() -> JsonPath.of("  ", KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY);
        assertThatThrownBy(() -> JsonPath.of(null, KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY);
    }

    @Test
    void anEmptySegmentIsRefusedRatherThanMatchingNothingForever() {
        for (final String path : new String[]{"a..b", "a.", ".a"}) {
            assertThatThrownBy(() -> JsonPath.of(path, KEY))
                    .as("'%s' means the operator meant something they did not write", path)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(KEY);
        }
    }
}
