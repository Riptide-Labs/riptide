package org.riptide.classification.internal.decision;

import lombok.AllArgsConstructor;
import lombok.ToString;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.internal.matcher.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;


/**
 * Classifies classification requests.
 * <p>
 * Classifiers are stored in leaf nodes of classification decision trees. They are derived from
 * {@link Rule}s. They contain a couple of
 * {@link Matcher}s that are checked during classification. The matchers may do a simplified test because
 * some of their rule's conditions may already have been covered by thresholds along the path through the
 * decision tree. Classifiers have the same sort ordered as their underlying rules.
 */
@ToString
public class Classifier implements Comparable<Classifier> {

    private static <RV> void addMatcher(List<Matcher> matchers, RV ruleValue, Function<RV, Matcher> matcherCreator) {
        if (ruleValue != null) {
            matchers.add(matcherCreator.apply(ruleValue));
        }
    }

    /**
     * Constructs a classifier for a rule simplifying its conditions corresponding to the given bounds.
     */
    public static Classifier of(final PreprocessedRule rule, final Bounds bounds) {
        final List<Matcher> matchers = new ArrayList<>();
        int matchedAspects = 0;
        if (rule.protocol != null) {
            matchedAspects++;
            addMatcher(matchers, rule.protocol.shrink(bounds.protocol), ProtocolMatcher::new);
        }
        if (rule.srcPort != null) {
            matchedAspects++;
            addMatcher(matchers, rule.srcPort.shrink(bounds.srcPort), SrcPortMatcher::new);
        }
        if (rule.dstPort != null) {
            matchedAspects++;
            addMatcher(matchers, rule.dstPort.shrink(bounds.dstPort), DstPortMatcher::new);
        }
        if (rule.srcAddr != null) {
            matchedAspects++;
            addMatcher(matchers, rule.srcAddr.shrink(bounds.srcAddr), SrcAddressMatcher::new);
        }
        if (rule.dstAddr != null) {
            matchedAspects++;
            addMatcher(matchers, rule.dstAddr.shrink(bounds.dstAddr), DstAddressMatcher::new);
        }
        return new Classifier(
                matchers.toArray(new Matcher[matchers.size()]),
                new Result(matchedAspects, rule.rule.getName()),
                rule.rule.getGroupPosition(),
                rule.rule.getPosition()
        );
    }

    public final Matcher[] matchers;
    public final Result result;
    public final int groupPosition, position;

    public Classifier(Matcher[] matchers, Result result, int groupPosition, int position) {
        this.matchers = matchers;
        this.result = result;
        this.groupPosition = groupPosition;
        this.position = position;
    }

    public Result classify(ClassificationRequest request) {
        for (var m : matchers) {
            if (!m.matches(request)) {
                return null;
            }
        }
        return result;
    }

    @Override
    public int compareTo(Classifier o) {
        return groupPosition < o.groupPosition ? -1 : groupPosition > o.groupPosition ? 1 :
                                                      position < o.position ? -1 : position > o.position ? 1 : 0;
    }

    @ToString
    @AllArgsConstructor
    public static class Result {
        // used to break ties in case that several classifiers with the same priority match
        public final int matchedAspects;
        public final String name;
    }
}
