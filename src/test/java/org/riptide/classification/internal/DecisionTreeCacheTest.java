/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import org.junit.jupiter.api.Test;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.decision.Tree;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The memoization behind #707: what counts as the same ruleset, and what the cache still holds
 * once other rulesets have been through it.
 *
 * <p>Trees here are {@link Tree#empty()} sentinels rather than built ones. Nothing the cache does
 * inspects a tree, and building one from the ruleset this exists for costs about 30s under the
 * coverage agent — a cost this class would pay on every run to assert nothing extra. That the
 * engine really reuses a real tree for the real bundled ruleset is
 * {@code DefaultClassificationEngineTest#aSecondEngineOverTheSameRulesServesTheSameTree}.
 */
class DecisionTreeCacheTest {

    /** All ten fields set to something, so the reflective walk below has a value to change. */
    private static Rule aRule(final String name, final int port) {
        return DefaultRule.builder()
                .withName(name)
                .withProtocol("tcp")
                .withSrcAddress("10.0.0.1")
                .withSrcPort(1024)
                .withDstAddress("10.0.0.2")
                .withDstPort(port)
                .withExporterFilter("exporter")
                .withGroupPosition(1)
                .withPosition(2)
                .withOmnidirectional(false)
                .build();
    }

    /** A fresh list, equal to but not the same object as the one it was keyed under. */
    private static List<Rule> ruleset(final int port) {
        return List.of(aRule("alpha", port), aRule("beta", port + 1));
    }

    @Test
    void theSameRulesInTheSameOrderHit() {
        final var cache = new DecisionTreeCache();
        final var tree = Tree.empty();

        cache.put(ruleset(80), tree);

        assertThat(cache.get(ruleset(80)))
                .as("an equal rule list is the same ruleset, whoever built the list")
                .containsSame(tree);
    }

    @Test
    void anUnknownRulesetMisses() {
        final var cache = new DecisionTreeCache();
        cache.put(ruleset(80), Tree.empty());

        assertThat(cache.get(ruleset(443))).isEmpty();
    }

    /**
     * The key must be field-complete. Enumerating the fields rather than listing them is the
     * point: a field added to {@link DefaultRule} later fails this test instead of silently
     * joining a key that ignores it, which would serve a tree built from different rules.
     */
    @Test
    void everyFieldOfDefaultRuleParticipatesInTheKey() {
        final var fields = Arrays.stream(DefaultRule.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !Modifier.isStatic(field.getModifiers()))
                .toList();
        assertThat(fields).as("a rule with no fields would make every ruleset the same one").isNotEmpty();

        for (final Field field : fields) {
            final var cache = new DecisionTreeCache();
            final var tree = Tree.empty();
            cache.put(List.of(aRule("alpha", 80)), tree);

            final var changed = aRule("alpha", 80);
            field.setAccessible(true);
            try {
                field.set(changed, differentValue(field, field.get(changed)));
            } catch (final IllegalAccessException e) {
                throw new AssertionError("cannot reach " + field.getName(), e);
            }

            assertThat(cache.get(List.of(changed)))
                    .as("a rule differing only in %s is a different rule", field.getName())
                    .isEmpty();
        }
    }

    /** Fails rather than skips on a field type it does not know: an unkeyed field is the defect. */
    private static Object differentValue(final Field field, final Object current) {
        final var type = field.getType();
        if (type == String.class) {
            return current == null ? "changed" : current + "-changed";
        } else if (type == int.class) {
            return (Integer) current + 1;
        } else if (type == boolean.class) {
            return !(Boolean) current;
        }
        return fail("DecisionTreeCacheTest has no different value for %s %s; teach it one rather "
                + "than letting the field go unchecked", type, field.getName());
    }

    /**
     * Order decides tie-breaks in the built tree, so a reordered ruleset is a different ruleset
     * and must not be served the first one's tree.
     */
    @Test
    void theSameRulesInADifferentOrderMiss() {
        final var cache = new DecisionTreeCache();
        cache.put(ruleset(80), Tree.empty());

        final var reversed = new ArrayList<>(ruleset(80));
        Collections.reverse(reversed);

        assertThat(cache.get(reversed)).isEmpty();
    }

    /**
     * The failure this cache is designed against. A full {@code mvn test} builds 31 other,
     * far smaller trees between its two builds of the bundled ruleset; a cache that keeps a
     * fixed number of entries loses the one entry it exists for and buys nothing, while still
     * looking implemented.
     */
    @Test
    void anEntrySurvivesTheThirtyOneSmallBuildsThatInterleave() {
        final var cache = new DecisionTreeCache();
        final var bundled = IntStream.range(0, 6248).mapToObj(i -> aRule("bundled-" + i, 1024 + i)).toList();
        final var tree = Tree.empty();
        cache.put(bundled, tree);

        for (int i = 0; i < 31; i++) {
            cache.put(List.of(aRule("interleaved-" + i, 80)), Tree.empty());
        }

        assertThat(cache.get(bundled))
                .as("31 one-rule builds must not evict the 6,248-rule one")
                .containsSame(tree);
    }

    /**
     * The bound is a stated number of retained rules, not an assumption. Sized here rather than
     * taken from {@link DecisionTreeCache#MAX_RETAINED_RULES}, whose 25,000 would need 25,000
     * rules to reach.
     */
    @Test
    void theBudgetEvictsTheLeastRecentlyUsedRuleset() {
        final var cache = new DecisionTreeCache(5);
        final var first = ruleset(80);
        final var second = ruleset(443);
        final var third = ruleset(8080);
        cache.put(first, Tree.empty());
        cache.put(second, Tree.empty());
        assertThat(cache.retainedRules()).isEqualTo(4);

        cache.get(first);
        cache.put(third, Tree.empty());

        assertThat(cache.retainedRules()).as("2 rulesets of 2 rules is all a budget of 5 holds").isEqualTo(4);
        assertThat(cache.get(second)).as("least recently used, so first out").isEmpty();
        assertThat(cache.get(first)).as("touched after it was stored").isPresent();
        assertThat(cache.get(third)).isPresent();
    }

    /** A ruleset bigger than the whole budget is simply not retained; it is not an error. */
    @Test
    void aRulesetLargerThanTheBudgetIsNotRetained() {
        final var cache = new DecisionTreeCache(1);

        cache.put(ruleset(80), Tree.empty());

        assertThat(cache.retainedRules()).isZero();
        assertThat(cache.get(ruleset(80))).isEmpty();
    }
}
