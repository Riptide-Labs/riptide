/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.matcher;

import org.riptide.classification.ClassificationRequest;

@FunctionalInterface
public interface Matcher {

    /**
     * Tests one aspect of a request against one rule's condition on it.
     * <p>
     * <strong>An aspect absent from the request is a non-match, never a throw.</strong> Every field of
     * a {@link ClassificationRequest} is nullable and an absent one is a designed state, not a broken
     * request: a rule may name an aspect the flow does not carry. The decision tree above these leaves
     * already says so, in
     * {@link org.riptide.classification.internal.decision.Threshold.Protocol#compare(ClassificationRequest)}
     * and its {@code Port} and {@code Address} siblings, each of which answers {@code Order.NA} for an
     * absent field and lets the tree route on it. A leaf that threw instead cost its flow's whole batch,
     * because {@code Pipeline.process} enriches a batch inside one try/catch (#750).
     * <p>
     * The complement matters as much: an absent aspect must not <em>match</em> either. A rule that names
     * an aspect claiming a flow that lacks it would silently reclassify traffic, which is worse than the
     * throw it replaced. The rules that name no such aspect are the ones that answer these flows.
     *
     * @param request the request to test, any field of which may be null
     * @return whether this rule's condition holds; {@code false} if the request lacks the aspect
     */
    boolean matches(ClassificationRequest request);
}
