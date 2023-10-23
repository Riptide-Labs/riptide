package org.riptide.classification;

import java.util.List;

public interface ClassificationEngine {
    interface ClassificationRulesReloadedListener {
        void classificationRulesReloaded(final List<Rule> rules);
    }

    String classify(ClassificationRequest classificationRequest);

    List<Rule> getInvalidRules();

    void reload() throws InterruptedException;

    void addClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener);

    void removeClassificationRulesReloadedListener(final ClassificationRulesReloadedListener classificationRulesReloadedListener);
}
