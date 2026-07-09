/*
 * Copyright 2023 Ronny Trommer <ronny@no42.org>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;

@FunctionalInterface
public interface Matcher {
    boolean matches(ClassificationRequest request);
}
