/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import lombok.extern.slf4j.Slf4j;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.decision.PreprocessedRule;
import org.riptide.classification.internal.decision.Tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A classification engine that uses a decision tree to select applicable classification rules.
 * <p>
 * The implementation is thread-safe.
 */
@Slf4j
public class DefaultClassificationEngine implements ClassificationEngine {

    /**
     * The single owner of the listener registrations for the whole engine stack — the wrappers pass through to
     * here. The declared type is load-bearing, not decoration: registrations arrive on caller threads while the
     * reload thread walks the list, so a plain {@link ArrayList} throws {@link java.util.ConcurrentModificationException}
     * out of {@code reload()}, and out of the <em>initial</em> reload that is a permanent outage rather than a
     * lost notification. Copy-on-write also gives {@link #fireClassificationReloadedListeners} its snapshot for
     * free, so no lock is ever held across a callback.
     */
    private final CopyOnWriteArrayList<ClassificationRulesReloadedListener> classificationRulesReloadedListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Everything one reload publishes, swapped in one reference write. The publication is {@code null} until the
     * first reload succeeds, which is what {@link #currentPublication()} reports as "nothing published yet".
     */
    private final AtomicReference<TreeAndPublication> treeAndPublication =
            new AtomicReference<>(new TreeAndPublication(Tree.empty(), null));

    private final ClassificationRuleProvider ruleProvider;

    public DefaultClassificationEngine(final ClassificationRuleProvider ruleProvider) throws InterruptedException {
        this(ruleProvider, true);
    }

    public DefaultClassificationEngine(final ClassificationRuleProvider ruleProvider, final boolean initialize) throws InterruptedException {
        this.ruleProvider = Objects.requireNonNull(ruleProvider);
        if (initialize) {
            this.reload();
        }
    }

    @Override
    public void reload() throws InterruptedException {
        var start = System.currentTimeMillis();
        var invalid = new ArrayList<Rule>();

        // Load all rules and validate them
        final List<PreprocessedRule> preprocessedRules = new ArrayList<>();
        final var rules = ruleProvider.getRules();
        rules.forEach(rule -> {
            try {
                final var preprocessedRule = PreprocessedRule.of(rule);
                preprocessedRules.add(preprocessedRule);
                if (rule.canBeReversed()) {
                    preprocessedRules.add(preprocessedRule.reverse());
                }
            } catch (Exception ex) {
                log.error("Rule {} is not valid. Ignoring rule.", rule, ex);
                invalid.add(rule);
            }
        });

        var tree = Tree.of(preprocessedRules);

        var elapsed = System.currentTimeMillis() - start;
        if (log.isInfoEnabled()) {
            var sb = new StringBuilder();
            sb
                    .append("calculated flow classification decision tree\n")
                    .append("time (ms): " + elapsed).append('\n')
                    .append("rules    : " + rules.size() + " (including reversed rules: " + preprocessedRules.size() + ")").append('\n')
                    .append("leaves   : " + tree.info.leaves).append('\n')
                    .append("nodes    : " + tree.info.nodes).append('\n')
                    .append("choices  : " + tree.info.choices).append(" (nodes with rules that ignore the aspect of the node's threshold)\n")
                    .append("minDepth : " + tree.info.minDepth).append('\n')
                    .append("maxDepth : " + tree.info.maxDepth).append('\n')
                    .append("avgDepth : " + (double) tree.info.sumDepth / tree.info.leaves).append('\n')
                    .append("minComp  : " + tree.info.minComp).append('\n')
                    .append("maxComp  : " + tree.info.maxComp).append('\n')
                    .append("avgComp  : " + (double) tree.info.sumComp / tree.info.leaves).append('\n')
                    .append("minLeafSize : " + tree.info.minLeafSize).append('\n')
                    .append("maxLeafSize : " + tree.info.maxLeafSize).append('\n')
                    .append("avgLeafSize : " + (double) tree.info.sumLeafSize / tree.info.leaves).append('\n');
            log.info(sb.toString());
        }

        final var publication = new Publication(rules, invalid);
        treeAndPublication.set(new TreeAndPublication(tree, publication));

        fireClassificationReloadedListeners(publication.rules());
    }

    /**
     * Delivers one publish to everything registered when the walk started.
     * <p>
     * Two properties, both of them the point of this method. The walk is over a copy-on-write snapshot and holds
     * no lock, so a listener is free to register, deregister, or ask this engine what was just published from
     * inside its own callback. And each listener is isolated: a consumer's bug becomes an ERROR here and the
     * delivery continues, because a throw escaping {@code reload()} fails the reload — and on the initial load
     * that leaves the wrapper above with nothing serviceable for the rest of the process.
     * <p>
     * {@code Throwable}, not {@code Exception}, and that width is load-bearing rather than defensive. The rules
     * are already published by the time this runs, so there is nothing left to abandon — whereas the realistic
     * consumer failure at boot is a {@code NoClassDefFoundError} from a listener touching a class that is not on
     * the path, and catching only {@code Exception} let exactly that reach {@code reload()} and produce the
     * permanent "no rules have ever been loaded" outage while a complete ruleset sat in {@link #treeAndPublication}.
     */
    private void fireClassificationReloadedListeners(final List<Rule> rules) {
        for (final ClassificationRulesReloadedListener classificationRulesReloadedListener : this.classificationRulesReloadedListeners) {
            try {
                classificationRulesReloadedListener.classificationRulesReloaded(rules);
            } catch (final Throwable ex) {
                log.error("Classification rules reloaded listener {} failed. The reloaded rules are published and "
                        + "the remaining listeners are still notified.", classificationRulesReloadedListener, ex);
            }
        }
    }

    @Override
    public List<Rule> getInvalidRules() {
        return currentPublication().map(Publication::invalidRules).orElseGet(Collections::emptyList);
    }

    @Override
    public Optional<Publication> currentPublication() {
        return Optional.ofNullable(treeAndPublication.get().publication);
    }

    public Tree getTree() {
        return treeAndPublication.get().tree;
    }

    @Override
    public String classify(ClassificationRequest classificationRequest) {
        return treeAndPublication.get().tree.classify(classificationRequest);
    }

    /** The tree and the publication it was built from, swapped together so neither can be read against the other. */
    private record TreeAndPublication(Tree tree, Publication publication) {
    }

    @Override
    public void addClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener) {
        this.classificationRulesReloadedListeners.add(classificationRulesReloadedListener);
    }

    @Override
    public void removeClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener) {
        this.classificationRulesReloadedListeners.remove(classificationRulesReloadedListener);
    }
}
