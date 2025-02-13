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
import java.util.concurrent.atomic.AtomicReference;

/**
 * A classification engine that uses a decision tree to select applicable classification rules.
 * <p>
 * The implementation is thread-safe.
 */
@Slf4j
public class DefaultClassificationEngine implements ClassificationEngine {

    private final AtomicReference<TreeAndInvalidRules> treeAndInvalidRules = new AtomicReference<>(new TreeAndInvalidRules(Tree.EMPTY, Collections.emptyList()));

    private final ClassificationRuleProvider ruleProvider;

    public DefaultClassificationEngine(final ClassificationRuleProvider ruleProvider) throws InterruptedException {
        this(ruleProvider, true);
    }

    public DefaultClassificationEngine(final ClassificationRuleProvider ruleProvider, final boolean shouldInitialize) throws InterruptedException {
        this.ruleProvider = Objects.requireNonNull(ruleProvider);
        if (shouldInitialize) {
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
            final var logMessage = "calculated flow classification decision tree\n"
                    + "time (ms): " + elapsed + '\n'
                    + "rules    : " + rules.size() + " (including reversed rules: " + preprocessedRules.size() + ")" + '\n'
                    + "leaves   : " + tree.info.leaves + '\n'
                    + "nodes    : " + tree.info.nodes + '\n'
                    + "choices  : " + tree.info.choices + " (nodes with rules that ignore the aspect of the node's threshold)\n"
                    + "minDepth : " + tree.info.minDepth + '\n'
                    + "maxDepth : " + tree.info.maxDepth + '\n'
                    + "avgDepth : " + (double) tree.info.sumDepth / tree.info.leaves + '\n'
                    + "minComp  : " + tree.info.minComp + '\n'
                    + "maxComp  : " + tree.info.maxComp + '\n'
                    + "avgComp  : " + (double) tree.info.sumComp / tree.info.leaves + '\n'
                    + "minLeafSize : " + tree.info.minLeafSize + '\n'
                    + "maxLeafSize : " + tree.info.maxLeafSize + '\n'
                    + "avgLeafSize : " + (double) tree.info.sumLeafSize / tree.info.leaves + '\n';
            log.info(logMessage);
        }

        treeAndInvalidRules.set(new TreeAndInvalidRules(tree, invalid));
    }

    @Override
    public List<Rule> getInvalidRules() {
        return Collections.unmodifiableList(treeAndInvalidRules.get().invalidRules);
    }

    @Override
    public String classify(ClassificationRequest classificationRequest) {
        return treeAndInvalidRules.get().tree.classify(classificationRequest);
    }

    private record TreeAndInvalidRules(Tree tree, List<Rule> invalidRules) {

    }
}
