/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.value.PortValue;

public class SrcPortMatcher extends PortMatcher {
    public SrcPortMatcher(final PortValue ports) {
        super(ports, ClassificationRequest::getSrcPort);
    }
    public SrcPortMatcher(final String ports) {
        this(PortValue.of(ports));
    }
}
