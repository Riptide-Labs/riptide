/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values.visitor;

import org.riptide.flows.parser.ie.values.BooleanValue;
import org.springframework.stereotype.Service;

@Service
public class BooleanVisitor implements ValueVisitor<Boolean> {
    @Override
    public Class<Boolean> targetClass() {
        return Boolean.class;
    }

    @Override
    public Boolean visit(BooleanValue value) {
        return value.getValue();
    }
}
