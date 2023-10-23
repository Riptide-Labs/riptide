package org.riptide.classification;

import java.util.List;

@FunctionalInterface
public interface ClassificationRuleProvider {
    List<Rule> getRules();

    static ClassificationRuleProvider forList(final List<Rule> rules) {
        return () -> rules;
    }
}
