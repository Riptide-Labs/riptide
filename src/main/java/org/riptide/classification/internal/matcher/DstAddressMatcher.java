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
