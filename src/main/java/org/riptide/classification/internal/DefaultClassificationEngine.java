package org.riptide.classification.internal;

import lombok.extern.slf4j.Slf4j;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.decision.PreprocessedRule;
import org.riptide.classification.internal.decision.Tree;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A classification engine that uses a decision tree to select applicable classification rules.
 * <p>
 * The implementation is thread-safe.
 */
@Slf4j
public class DefaultClassificationEngine implements ClassificationEngine {

    private List<ClassificationRulesReloadedListener> classificationRulesReloadedListeners = new ArrayList<>();

    private final AtomicReference<TreeAndInvalidRules> treeAndInvalidRules = new AtomicReference<>(new TreeAndInvalidRules(Tree.EMPTY, Collections.emptyList()));

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
                LoggerFactory.getLogger(getClass()).error("Rule {} is not valid. Ignoring rule.", rule, ex);
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

        treeAndInvalidRules.set(new TreeAndInvalidRules(tree, invalid));

        fireClassificationReloadedListeners(Collections.unmodifiableList(rules));
    }

    private void fireClassificationReloadedListeners(final List<Rule> rules) {
        for (final ClassificationRulesReloadedListener classificationRulesReloadedListener : this.classificationRulesReloadedListeners) {
            classificationRulesReloadedListener.classificationRulesReloaded(rules);
        }
    }

    @Override
    public List<Rule> getInvalidRules() {
        return Collections.unmodifiableList(treeAndInvalidRules.get().invalidRules);
    }

    public Tree getTree() {
        return treeAndInvalidRules.get().tree;
    }

    @Override
    public String classify(ClassificationRequest classificationRequest) {
        return treeAndInvalidRules.get().tree.classify(classificationRequest);
    }

    private static final class TreeAndInvalidRules {
        private final Tree tree;
        private final List<Rule> invalidRules;
        private TreeAndInvalidRules(Tree tree, List<Rule> invalidRules) {
            this.tree = tree;
            this.invalidRules = invalidRules;
        }
    }

    public void addClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener) {
        this.classificationRulesReloadedListeners.add(classificationRulesReloadedListener);
    }

    public void removeClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener) {
        this.classificationRulesReloadedListeners.remove(classificationRulesReloadedListener);
    }
}
