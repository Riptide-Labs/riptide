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
