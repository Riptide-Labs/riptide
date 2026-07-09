/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification;

import java.util.List;

@FunctionalInterface
public interface ClassificationRuleProvider {
    List<Rule> getRules();

    static ClassificationRuleProvider forList(final List<Rule> rules) {
        return () -> rules;
    }
}
