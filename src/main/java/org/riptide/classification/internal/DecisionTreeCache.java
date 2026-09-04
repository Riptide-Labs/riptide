/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import org.riptide.classification.Rule;
import org.riptide.classification.internal.decision.Tree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Remembers the decision tree a rule list built, so a ruleset already built in this JVM is not
 * built again (#707).
 *
 * <p>The build is near-quadratic in rule count — measured on the bundled 6,248-rule ruleset:
 * 1.67&nbsp;s uninstrumented, ~30&nbsp;s under the coverage agent — and it is a pure function of
 * its input, so the second build of the same rules is pure waste. This removes the repeats only;
 * a cold boot still pays for the first one.
 *
 * <p>Three properties are load-bearing, not incidental:
 *
 * <ul>
 *   <li><b>The key is the rules themselves, never a digest.</b> {@link org.riptide.classification.DefaultRule}
 *       is the only implementation of {@link Rule} and is a Lombok {@code @Data} class, so list
 *       equality is field-complete and order-sensitive. A hash key would trade a silent wrong
 *       tree — a misclassification nobody sees — for a few bytes.
 *   <li><b>The bound is a count of rules, not a count of entries.</b> {@link #MAX_RETAINED_RULES}
 *       is what this retains, stated exactly; an entry cap would bound the number of trees while
 *       leaving their size, and therefore the footprint, an assumption.
 *   <li><b>Small builds cannot evict a large one.</b> That follows from bounding on rules: the 31
 *       one- and two-rule trees a full test suite builds between its two bundled-ruleset builds
 *       consume ~50 of the budget, so the entry the cache exists for is still there for the
 *       second. A least-recently-used entry cap would have to be sized above that interleaving to
 *       survive it, which is a tuning number pretending to be a design.
 * </ul>
 *
 * <p>The trees handed out are shared between callers and must therefore stay immutable. They are:
 * every field reachable from a built {@link Tree} is final. The two mutable classes in the
 * {@code decision} package — the classifier iterators {@code Tree} creates inside
 * {@code classifiers(request)} — are allocated per request and never reach a field of the tree.
 *
 * <p>Thread-safe. No lock is held across a build: {@link #get} and {@link #put} each lock, and the
 * build happens between them on the caller's thread. Two threads racing on the same new ruleset
 * may therefore both build it, which is correct and no worse than today — whereas a monitor held
 * for the length of a build would be a new startup stall.
 */
final class DecisionTreeCache {

    /**
     * The retained bound, in rules, counted over the keys of every entry held.
     *
     * <p>Four times the bundled ruleset's 6,248, which is room for a few distinct rulesets of
     * today's size plus everything small a suite interleaves. Stated in rules because that is the
     * quantity this can count exactly; the bytes one rule's share of a tree costs are not measured
     * here, so no byte figure is claimed.
     */
    static final int MAX_RETAINED_RULES = 25_000;

    /** The process-wide instance, so a ruleset built by one engine is not rebuilt by the next. */
    private static final DecisionTreeCache SHARED = new DecisionTreeCache();

    static DecisionTreeCache shared() {
        return SHARED;
    }

    /** A held tree, with the size of the rule list that built it so eviction can charge for it. */
    private record Entry(Tree tree, int rules) {
    }

    private final int maxRetainedRules;

    /** Access-ordered, so {@link #evictDownToBudget} drops the least recently used entry first. */
    private final LinkedHashMap<List<Rule>, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    private int retainedRules;

    DecisionTreeCache() {
        this(MAX_RETAINED_RULES);
    }

    DecisionTreeCache(final int maxRetainedRules) {
        this.maxRetainedRules = maxRetainedRules;
    }

    /**
     * The key for a rule list: a defensive immutable copy, so a provider that reuses its list
     * cannot mutate a key already in the map.
     */
    private static List<Rule> key(final List<Rule> rules) {
        return List.copyOf(rules);
    }

    /** @return the tree these exact rules, in this exact order, already built */
    synchronized Optional<Tree> get(final List<Rule> rules) {
        final var entry = this.entries.get(key(rules));
        return entry == null ? Optional.empty() : Optional.of(entry.tree());
    }

    /** Remembers {@code tree} as what {@code rules} builds, evicting older entries to stay in budget. */
    synchronized void put(final List<Rule> rules, final Tree tree) {
        final var stored = key(rules);
        final var previous = this.entries.put(stored, new Entry(tree, stored.size()));
        if (previous == null) {
            this.retainedRules += stored.size();
        }
        evictDownToBudget();
    }

    /**
     * Drops least-recently-used entries until the retained rule count fits the budget.
     *
     * <p>A single ruleset larger than the whole budget evicts itself here and is simply not
     * retained. The caller still gets the tree it just built; only the next build of it pays again.
     */
    private void evictDownToBudget() {
        final var iterator = this.entries.entrySet().iterator();
        while (this.retainedRules > this.maxRetainedRules && iterator.hasNext()) {
            this.retainedRules -= iterator.next().getValue().rules();
            iterator.remove();
        }
    }

    /** The rules currently charged against {@link #MAX_RETAINED_RULES}. */
    synchronized int retainedRules() {
        return this.retainedRules;
    }
}
