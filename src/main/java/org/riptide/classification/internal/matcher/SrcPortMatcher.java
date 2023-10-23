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
