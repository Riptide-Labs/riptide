/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.value.IpValue;

public class DstAddressMatcher extends IpMatcher {
    public DstAddressMatcher(final IpValue input) {
        super(input, ClassificationRequest::getDstAddress);
    }
    public DstAddressMatcher(final String input) {
        this(IpValue.of(input));
    }
}
