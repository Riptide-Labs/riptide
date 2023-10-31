package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;

@FunctionalInterface
public interface Matcher {
    boolean matches(ClassificationRequest request);
}
