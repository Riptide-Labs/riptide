/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.value.PortValue;

public class DstPortMatcher extends PortMatcher {
    public DstPortMatcher(final PortValue ports) {
        super(ports, ClassificationRequest::getDstPort);
    }
    public DstPortMatcher(final String ports) {
        this(PortValue.of(ports));
    }
}
