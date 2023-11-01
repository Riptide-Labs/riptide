package org.riptide.classification.internal.value;

import org.riptide.classification.internal.decision.Bound;

public interface RuleValue<S extends Comparable<S>, T extends RuleValue<S, T>> {

    /**
     * Shrinks this rule value by removing those parts that are already covered by the given bound.
     * <p>
     * The given bounds result from thresholds along paths in the decision tree. During
     * classification those parts that are covered by these threshold need not to be checked again.
     *
     * @return Returns a shrunk rule value or {@code null} if this rule value is completely covered by the given bound.
     */
    T shrink(Bound<S> bound);
}
