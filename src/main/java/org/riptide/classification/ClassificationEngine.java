/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import java.util.List;

public interface ClassificationEngine {
    interface ClassificationRulesReloadedListener {
        void classificationRulesReloaded(List<Rule> rules);
    }

    String classify(ClassificationRequest classificationRequest);

    List<Rule> getInvalidRules();

    void reload() throws InterruptedException;

    void addClassificationRulesReloadedListener(ClassificationRulesReloadedListener classificationRulesReloadedListener);

    void removeClassificationRulesReloadedListener(ClassificationRulesReloadedListener classificationRulesReloadedListener);
}
