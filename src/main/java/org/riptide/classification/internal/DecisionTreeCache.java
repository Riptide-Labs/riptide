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
 * <h2>This is a build-time optimisation, not a production fix</h2>
 *
 * <p>Say so plainly, because the code invites the opposite reading. A production boot builds the
 * tree exactly <em>once</em>: {@link org.riptide.configuration.RiptideConfiguration} constructs the
 * engine with {@code initialize=false} and the single initial load is done for it. Every later
 * reload arrives through {@link ClassificationRuleReloader}'s {@code onContent} branch, which fires
 * only when the fetched bytes changed — changed bytes mean different rules, a different key, and
 * therefore a guaranteed <em>miss</em>. The only production hit available is an exact revert,
 * A&nbsp;-&gt;&nbsp;B&nbsp;-&gt;&nbsp;A, with the A entry not yet evicted.
 *
 * <p>What production pays is unconditional: {@link #SHARED} is a permanent process-wide heap root.
 * Measured, not modelled — four bundled trees built and held, forced GC, heap delta — one tree for
 * the bundled 6,248-rule ruleset retains <b>2.8&nbsp;MB</b>, so {@link #MAX_RETAINED_RULES} is
 * about <b>11&nbsp;MB</b> worst case. That is the whole trade, and it is a test-suite one: 11 MB of
 * permanently retained heap for one fewer 30-second tree build per CI run. A full {@code mvn test}
 * built the bundled ruleset twice, at 26&nbsp;s and 35&nbsp;s under the coverage agent; it builds it
 * once as long as every class that wants the bundled tree takes it from an engine.
 *
 * <p>A test that calls {@code Tree.of} itself silently restores the second build — silently, because
 * the build counter is a log line {@code DefaultClassificationEngine} emits and {@code Tree.of} does
 * not, so a bypassing build costs the 30 seconds without moving the count. That has happened once
 * already, in {@code BundledRulesetTreeIdentityTest}, which now takes its tree from an engine.
 *
 * <h2>Why the key is sound</h2>
 *
 * <p>The key is the rule list itself, never a digest — a hash key would trade a silent wrong tree,
 * which is a misclassification nobody sees, for a few bytes.
 *
 * <p>That rests on the lists that reach it having value equality, and the reason is narrower than
 * "{@code DefaultRule} is the only {@link Rule}", which is false: {@link Rule#reversedRule()}
 * returns an anonymous implementation with identity equality. It cannot reach a key today, because
 * reversal happens on the <em>preprocessed</em> rules inside {@code DefaultClassificationEngine} and
 * the key is the raw provider list. So what actually holds is: every list keyed here comes from a
 * {@link org.riptide.classification.ClassificationRuleProvider} returning {@code DefaultRule}s,
 * which are Lombok {@code @Data} and therefore field-complete and order-sensitive in equality.
 *
 * <p>If that ever stopped holding, the failure is benign rather than silent: a {@code Rule} with
 * identity equality never matches a stored key, so the cache misses forever and rebuilds. It cannot
 * produce a tree built from different rules.
 *
 * <h2>What the bound is, and what it does not promise</h2>
 *
 * <p>The bound is a count of retained rules rather than of entries, so the footprint is stated
 * rather than assumed: an entry cap bounds the number of trees and leaves their size unknown.
 *
 * <p>It also keeps a test suite's small builds from evicting the entry the cache exists for — but
 * that is a property of today's sizes, not a guarantee. Measured on the pre-cache suite log: the 31
 * builds that land between the two bundled builds are rulesets of 1, 2, 3 and 25 rules, summing
 * <b>64</b> against a 25,000 budget. Thirty-one interleaved 800-rule builds would evict a bundled
 * entry at this budget, and nothing here would report it — the cache would simply stop paying and
 * still look implemented. The mechanical detector is the build count in a suite log, not this class.
 *
 * <h2>Sharing and locking</h2>
 *
 * <p>The trees handed out are shared between callers and must therefore stay immutable. They are:
 * every field reachable from a built {@link Tree} is final. The two mutable classes in the
 * {@code decision} package — the classifier iterators {@code Tree} creates inside
 * {@code classifiers(request)} — are allocated per request and never reach a field of the tree.
 *
 * <p>Thread-safe. No lock is held across a build: {@link #get} and {@link #put} each lock, and the
 * build happens between them on the caller's thread. Two threads racing on the same new ruleset may
 * therefore both build it, which is correct and no worse than before — whereas a monitor held for
 * the length of a build would be a new startup stall.
 */
final class DecisionTreeCache {

    /**
     * The retained bound, in rules, counted over the keys of every entry held.
     *
     * <p>Four times the bundled ruleset's 6,248, which at the measured 2.8&nbsp;MB per bundled tree
     * is about 11&nbsp;MB. Stated in rules because that is the quantity this can count exactly.
     */
    static final int MAX_RETAINED_RULES = 25_000;

    /**
     * The process-wide instance, and therefore a permanent heap root for the life of the JVM.
     *
     * <p>Static because the win is a cross-class one: the two bundled builds a full test suite pays
     * for are in different classes with different engines, so an instance field on the engine would
     * hit nothing. In production it retains one ruleset that will not be asked for again — see the
     * class javadoc for why a production reload is a guaranteed miss.
     */
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
     * @return the tree these exact rules, in this exact order, already built
     */
    synchronized Optional<Tree> get(final List<Rule> rules) {
        // The caller's list is used as the lookup key with no copy: List.equals and List.hashCode
        // are specified across implementations, so any equal list finds the entry. The copy in
        // put() is a storage concern, not a key-identity one, and doing it here would allocate a
        // 6,248-element array on every reload to answer a question that does not need one.
        final var entry = this.entries.get(rules);
        return entry == null ? Optional.empty() : Optional.of(entry.tree());
    }

    /** Remembers {@code tree} as what {@code rules} builds, evicting older entries to stay in budget. */
    synchronized void put(final List<Rule> rules, final Tree tree) {
        if (rules.size() > this.maxRetainedRules) {
            // Refused before it is charged, not evicted after. Charging it first would put the map
            // over budget by more than this entry's own size, and eviction walks from the
            // least-recently-used end — so one oversized ruleset would drop every entry that was
            // earning its keep and then itself, leaving the cache empty. The caller still has the
            // tree it just built; only the next build of this ruleset pays again.
            return;
        }
        // Snapshot, so a provider that reuses and refills its list cannot mutate a key already in
        // the map — which would leave the entry unreachable and its budget charged forever.
        final var stored = List.copyOf(rules);
        final var previous = this.entries.put(stored, new Entry(tree, stored.size()));
        if (previous == null) {
            this.retainedRules += stored.size();
        }
        evictDownToBudget();
    }

    /** Drops least-recently-used entries until the retained rule count fits the budget. */
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
