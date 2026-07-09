/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.value.IpValue;

public class SrcAddressMatcher extends IpMatcher {
    public SrcAddressMatcher(final IpValue input) {
        super(input, ClassificationRequest::getSrcAddress);
    }
    public SrcAddressMatcher(final String input) {
        this(IpValue.of(input));
    }
}
