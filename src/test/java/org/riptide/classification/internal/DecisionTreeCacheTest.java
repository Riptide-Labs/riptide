/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.decision.Classifier;
import org.riptide.classification.internal.decision.Tree;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The memoization behind #707: what counts as the same ruleset, what the cache still holds once
 * other rulesets have been through it, and what it refuses to hold.
 *
 * <p>Trees here are cheap stand-ins rather than built ones. Nothing the cache does inspects a tree,
 * and building one from the ruleset this exists for costs about 30s under the coverage agent — a
 * cost this class would pay on every run to assert nothing extra. That the engine really reuses a
 * real tree for the real bundled ruleset is
 * {@link DefaultClassificationEngineTest#aSecondEngineOverTheSameRulesServesTheSameTree}.
 */
class DecisionTreeCacheTest {

    /**
     * A distinct tree per call, which {@link Tree#empty()} is not: it is a singleton, so a row
     * asserting that one entry was served rather than another would pass on either.
     */
    private static Tree aTree() {
        return new Tree.Leaf.WithClassifiers(List.<Classifier>of());
    }

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
        final var tree = aTree();

        cache.put(ruleset(80), tree);

        assertThat(cache.get(ruleset(80)))
                .as("an equal rule list is the same ruleset, whoever built the list")
                .containsSame(tree);
    }

    @Test
    void anUnknownRulesetMisses() {
        final var cache = new DecisionTreeCache();
        cache.put(ruleset(80), aTree());

        assertThat(cache.get(ruleset(443))).isEmpty();
    }

    /**
     * The key is a snapshot taken at {@code put}. A provider free to reuse and refill its list
     * would otherwise mutate a key already in the map, leaving that entry unreachable — its hash
     * bucket no longer matching — while its size stayed charged against the budget forever.
     */
    @Test
    void theKeyIsSnapshotSoTheCallersListCannotMoveIt() {
        final var cache = new DecisionTreeCache();
        final var tree = aTree();
        final var provided = new ArrayList<>(ruleset(80));

        cache.put(provided, tree);
        provided.add(aRule("added-after-the-put", 8080));

        assertThat(cache.get(ruleset(80)))
                .as("the rules that were keyed, whatever the caller did to its own list afterwards")
                .containsSame(tree);
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
            cache.put(List.of(aRule("alpha", 80)), aTree());

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

    /**
     * Fails rather than skips on a field type it does not know: an unkeyed field is the defect.
     *
     * <p>Nullness here is asymmetric, and stating that is the point of the shape below. A
     * {@code String} field is legitimately null — {@link DefaultRule} leaves most of them unset —
     * so on that branch absence is a value to change rather than an error. A primitive field
     * cannot be: reflection boxes an {@code int} or a {@code boolean} read off a constructed
     * instance, so {@code current} is non-null there by construction. The explicit null test on
     * the {@code String} branch is what makes the parameter look nullable to a static analyser,
     * which then reads the unboxing below as an unguarded dereference; the guard says which of the
     * two cases this is instead of pretending a null primitive could arrive.
     */
    private static Object differentValue(final Field field, final Object current) {
        final var type = field.getType();
        if (type == String.class) {
            return current == null ? "changed" : current + "-changed";
        }
        if (type != int.class && type != boolean.class) {
            // Before the guard below, so an unhandled type still fails with the message that says
            // what to do about it rather than with a null dereference.
            return fail("DecisionTreeCacheTest has no different value for %s %s; teach it one rather "
                    + "than letting the field go unchecked", type, field.getName());
        }
        final var value = Objects.requireNonNull(current,
                "reflection returns a boxed value for primitive field " + field.getName());
        if (type == int.class) {
            return (Integer) value + 1;
        }
        return !(Boolean) value;
    }

    /**
     * Order decides tie-breaks in the built tree, so a reordered ruleset is a different ruleset
     * and must not be served the first one's tree.
     */
    @Test
    void theSameRulesInADifferentOrderMiss() {
        final var cache = new DecisionTreeCache();
        cache.put(ruleset(80), aTree());

        final var reversed = new ArrayList<>(ruleset(80));
        Collections.reverse(reversed);

        assertThat(cache.get(reversed)).isEmpty();
    }

    /**
     * The failure this cache is designed against. A full {@code mvn test} built 31 other trees
     * between its two builds of the bundled ruleset; a cache that keeps a fixed number of entries
     * loses the one entry it exists for and buys nothing, while still looking implemented.
     */
    @Test
    void anEntrySurvivesTheThirtyOneSmallBuildsThatInterleave() {
        final var cache = new DecisionTreeCache();
        final var bundled = IntStream.range(0, 6248).mapToObj(i -> aRule("bundled-" + i, 1024 + i)).toList();
        final var tree = aTree();
        cache.put(bundled, tree);

        for (int i = 0; i < 31; i++) {
            cache.put(List.of(aRule("interleaved-" + i, 80)), aTree());
        }

        assertThat(cache.get(bundled))
                .as("31 one-rule builds must not evict the 6,248-rule one")
                .containsSame(tree);
    }

    /**
     * The default bound is real, which is the difference between a stated footprint and an
     * unbounded static that nobody notices.
     */
    @Test
    void theDefaultBudgetBoundsWhatIsRetained() {
        final var cache = new DecisionTreeCache();
        final var rulesets = new ArrayList<List<Rule>>();
        for (int i = 0; i < 6; i++) {
            final int nth = i;
            rulesets.add(IntStream.range(0, 5_000).mapToObj(r -> aRule("r-" + nth + "-" + r, 1024)).toList());
            cache.put(rulesets.get(i), aTree());
        }

        // The literal is deliberate. Asserting against MAX_RETAINED_RULES itself would move with
        // the constant, so raising it to Integer.MAX_VALUE would keep this row green while
        // shipping an unbounded process-wide cache.
        assertThat(cache.retainedRules())
                .as("30,000 rules offered against a default bound of 25,000")
                .isLessThanOrEqualTo(25_000);
        assertThat(cache.get(rulesets.get(0))).as("least recently used, so gone").isEmpty();
        assertThat(cache.get(rulesets.get(5))).as("most recently used, so held").isPresent();
    }

    /**
     * The bound is a stated number of retained rules, not an assumption. Sized here rather than
     * taken from {@link DecisionTreeCache#MAX_RETAINED_RULES}, whose 25,000 needs 25,000 rules to
     * reach; that the default is enforced at all is {@link #theDefaultBudgetBoundsWhatIsRetained}.
     */
    @Test
    void theBudgetEvictsTheLeastRecentlyUsedRuleset() {
        final var cache = new DecisionTreeCache(5);
        final var first = ruleset(80);
        final var second = ruleset(443);
        final var third = ruleset(8080);
        final var firstTree = aTree();
        cache.put(first, firstTree);
        cache.put(second, aTree());
        assertThat(cache.retainedRules()).isEqualTo(4);

        cache.get(first);
        cache.put(third, aTree());

        assertThat(cache.retainedRules()).as("2 rulesets of 2 rules is all a budget of 5 holds").isEqualTo(4);
        assertThat(cache.get(second)).as("least recently used, so first out").isEmpty();
        assertThat(cache.get(first)).as("touched after it was stored").containsSame(firstTree);
        assertThat(cache.get(third)).isPresent();
    }

    /**
     * A ruleset bigger than the whole budget is refused, and refusing it must not cost the entries
     * that do fit. Charging it first and evicting afterwards would walk from the least-recently-used
     * end and empty the cache before dropping the oversized entry itself.
     */
    @Test
    void aRulesetLargerThanTheBudgetIsRefusedWithoutEmptyingTheCache() {
        final var cache = new DecisionTreeCache(3);
        final var kept = ruleset(80);
        final var keptTree = aTree();
        cache.put(kept, keptTree);

        final var oversized = List.of(aRule("a", 1), aRule("b", 2), aRule("c", 3), aRule("d", 4));
        cache.put(oversized, aTree());

        assertThat(cache.get(oversized)).as("bigger than the whole budget, so not retained").isEmpty();
        assertThat(cache.get(kept))
                .as("and it must not take the entries that were earning their keep down with it")
                .containsSame(keptTree);
        assertThat(cache.retainedRules()).isEqualTo(2);
    }

    /**
     * Hits and evicting puts at the same time, which is the pair that actually contends. A hit
     * relinks the access order and bumps the map's modification count; an evicting put is walking
     * that same entry set. Unless both sit inside one monitor the walk sees a concurrent
     * modification, so this fails loudly rather than by corrupting an entry quietly.
     *
     * <p>A pair of threads merely building the same new ruleset does not test this: both miss,
     * and a miss neither relinks nor evicts. That row is
     * {@link DefaultClassificationEngineTest#twoThreadsRacingOnTheSameNewRulesetBothGetACorrectTree},
     * and it owns a different property — that each caller ends up with a usable tree.
     */
    @Test
    @Timeout(60)
    void concurrentHitsAndEvictingPutsDoNotCorruptTheCache() throws InterruptedException {
        final var cache = new DecisionTreeCache(200);
        final var hot = ruleset(80);
        cache.put(hot, aTree());

        final var failures = new CopyOnWriteArrayList<Throwable>();
        final var stop = new AtomicBoolean();
        final var readers = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            final var reader = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        cache.get(hot);
                    }
                } catch (final Throwable t) {
                    failures.add(t);
                }
            }, "cache-reader-" + i);
            readers.add(reader);
            reader.start();
        }

        try {
            for (int i = 0; i < 20_000; i++) {
                cache.put(List.of(aRule("cold-a-" + i, 1024), aRule("cold-b-" + i, 1025)), aTree());
            }
        } finally {
            stop.set(true);
            for (final var reader : readers) {
                reader.join();
            }
        }

        assertThat(failures).as("reading a cached tree while another thread evicts").isEmpty();
        assertThat(cache.retainedRules())
                .as("and the budget accounting survived the storm")
                .isBetween(0, 200);
    }
}
