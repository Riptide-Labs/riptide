/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.decision;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.IpAddr;
import org.riptide.classification.internal.value.IpValue;
import org.riptide.classification.internal.value.PortValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Represents a threshold that divides rules sets during decision tree construction
 * and guides tree traversal during classification.
 */
public abstract class Threshold<T extends Comparable<T>> {

    /**
     * Holds the result of matching a collection of classification rules against a threshold.
     * <p>
     * Rules may be included in zero, one, or more collections.
     */
    public static class Matches {
        public final List<PreprocessedRule> lt, eq, gt, na;

        public Matches(List<PreprocessedRule> lt, List<PreprocessedRule> eq, List<PreprocessedRule> gt, List<PreprocessedRule> na) {
            this.lt = lt;
            this.eq = eq;
            this.gt = gt;
            this.na = na;
        }
    }

    /**
     * Holds how many rules of a collection fall into each bucket when it is matched against a threshold.
     * <p>
     * These are exactly the sizes of the four {@link Matches} collections, arrived at without building them.
     * As with {@link Matches}, a rule may be counted in more than one bucket.
     */
    record Counts(int lt, int eq, int gt, int na) {
    }

    /**
     * Indicates the order of a classification request relative to a threshold.
     */
    public enum Order {
        LT,
        EQ,
        GT,
        NA // indicates that a classification request can not be compared to a threshold because it does not have a corresponding value
    }

    /**
     * Bundles the information how a rule matches a threshold. More than one flag may be {@code true}.
     * Package-private (not private) because the protected {@code match} implementations in the
     * nested subclasses reference it in their signatures.
     * <p>
     * A {@code Match} is allocated per rule per candidate threshold at every node, which makes it look
     * like the obvious next thing to optimize after #746: the three per-rule {@code match} sites all
     * pass {@code na = false} — the fourth construction is {@link Match#NA} itself — so there are only
     * eight distinct values reachable from them, and they could be interned in a table the way
     * {@code NA} already is. That was built and measured, and it did not pay. Best of 3,
     * JIT warmed, no coverage agent, fifteen interleaved pairs on one machine: allocating won 12 of the
     * 15, median 1023ms against 1049ms interned. The tree was identical either way. Why the shared table
     * is no faster was not established — disabling escape analysis slowed both variants by a similar
     * amount, so it does not show the allocation being eliminated — but the outcome is measured and the
     * work is not worth redoing without a different idea about the mechanism.
     */
    static final class Match {
        final boolean lt, eq, gt, na;

        Match(boolean lt, boolean eq, boolean gt, boolean na) {
            this.lt = lt;
            this.eq = eq;
            this.gt = gt;
            this.na = na;
        }

        static final Match NA = new Match(false, false, false, true);
    }

    protected final Function<Bounds, Bound<T>> getBound;
    private final BiFunction<Bounds, Bound<T>, Bounds> setBound;

    public Threshold(
            Function<Bounds, Bound<T>> getBound,
            BiFunction<Bounds, Bound<T>, Bounds> setBound
    ) {
        this.getBound = getBound;
        this.setBound = setBound;
    }

    // the threshold values are stored in subclasses for two reasons:
    // 1. in case of protocol and port thresholds are primitives that have far better performance
    // 2. avoid pitfall: while "<" and ">" work as expected when working with boxed numbers
    //    "==" checks object identity. This leads to subtle bugs in the match and compare methods.
    public abstract T getThreshold();

    /**
     * Checks for every rule if it matches values that are less than, equal to, or greater than this threshold and
     * adds that rule the corresponding collections. Rules that do not have a value corresponding to this
     * threshold are added to the {@link Matches#na} collection.
     * <p>
     * This method is used to build a decision tree for rule sets.
     * <p>
     * <strong>Note:</strong> A rule may be added to more than one collection. For example rules may cover
     * IP address ranges that include an address threshold. In that case the rule is added to the lt, eq, and gt
     * collections.
     */
    public Matches match(Collection<PreprocessedRule> ruleSet, Bounds bounds) {
        var lt = new ArrayList<PreprocessedRule>();
        var eq = new ArrayList<PreprocessedRule>();
        var gt = new ArrayList<PreprocessedRule>();
        var na = new ArrayList<PreprocessedRule>();
        for (var rule : ruleSet) {
            var cr = match(rule, bounds);
            if (cr.lt) {
                lt.add(rule);
            }
            if (cr.eq) {
                eq.add(rule);
            }
            if (cr.gt) {
                gt.add(rule);
            }
            if (cr.na) {
                na.add(rule);
            }
        }
        return new Matches(optimize(lt), optimize(eq), optimize(gt), optimize(na));
    }

    /**
     * Counts how many rules of the given set fall into each of the buckets that
     * {@link #match(Collection, Bounds)} would fill, without building the collections.
     * <p>
     * Tree construction scores every candidate threshold but reads only the bucket sizes; the collections
     * themselves are consumed for the winning candidate alone. {@code ThresholdCountsTest} pins that the
     * two agree, and {@code TreeScoringDoesNotBuildListsTest} pins that scoring really takes this route.
     * <p>
     * Sharing {@link #match(PreprocessedRule, Bounds)} with the list-building method is not by itself
     * what makes the split safe. {@code Tree.of} calls this for every candidate and then calls
     * {@code match} again for the winner, so what identity actually rests on is that
     * {@code match(PreprocessedRule, Bounds)} is <em>deterministic across two separate invocations</em>
     * — a shared call site says nothing about calling it twice. It is: all three implementations read
     * only final fields of the rule and the immutable {@link Bounds}, and none of them memoises. A
     * future implementation that cached, or that read anything mutable, would break tree identity while
     * leaving the "same call" reasoning looking sound, so it is the determinism that has to be preserved
     * here, not the sharing.
     */
    Counts count(Collection<PreprocessedRule> ruleSet, Bounds bounds) {
        var lt = 0;
        var eq = 0;
        var gt = 0;
        var na = 0;
        for (var rule : ruleSet) {
            var cr = match(rule, bounds);
            if (cr.lt) {
                lt++;
            }
            if (cr.eq) {
                eq++;
            }
            if (cr.gt) {
                gt++;
            }
            if (cr.na) {
                na++;
            }
        }
        return new Counts(lt, eq, gt, na);
    }

    private static <T> List<T> optimize(List<T> list) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        } else if (list.size() == 1) {
            return Collections.singletonList(list.get(0));
        } else {
            return list;
        }
    }

    /**
     * Checks if the given request has a value that is less than, equal to, or greater than the threshold value.
     * In case that the given request has no value that corresponds to this threshold {@link Order#NA} is returned.
     */
    public abstract Order compare(ClassificationRequest request);

    public final boolean canRestrict(Bounds bounds) {
        return getBound.apply(bounds).canBeRestrictedBy(getThreshold());
    }

    /** Uses this threshold to restrict the corresponding bound in the given bounds. */
    public final Bounds lt(Bounds bounds) {
        return setBound.apply(bounds, getBound.apply(bounds).lt(getThreshold()));
    }

    /** Uses this threshold to restrict the corresponding bound in the given bounds. */
    public final Bounds eq(Bounds bounds) {
        return setBound.apply(bounds, getBound.apply(bounds).eq(getThreshold()));
    }

    /** Uses this threshold to restrict the corresponding bound in the given bounds. */
    public final Bounds gt(Bounds bounds) {
        return setBound.apply(bounds, getBound.apply(bounds).gt(getThreshold()));
    }

    /**
     * Checks if the given rule matches values that are less than, equal to, or greater than this threshold.
     * <p>
     * The given bounds are also considered. A rule matches only if it specifies values within the given bounds.
     */
    protected abstract Match match(PreprocessedRule rule, Bounds bounds);

    @ToString
    @EqualsAndHashCode(callSuper = false)
    public static final class Protocol extends Threshold<Integer> {

        private final int protocol;

        public Protocol(int protocol) {
            super(
                    bs -> bs.protocol,
                    (bs, b) -> new Bounds(b, bs.srcPort, bs.dstPort, bs.srcAddr, bs.dstAddr)
            );
            this.protocol = protocol;
        }

        @Override
        public Integer getThreshold() {
            return protocol;
        }

        @Override
        protected Match match(PreprocessedRule rule, Bounds bounds) {
            if (rule.protocol == null) {
                return Match.NA;
            } else {
                var lt = false;
                var eq = false;
                var gt = false;
                for (int p : rule.protocol.getProtocols()) {
                    if (!bounds.protocol.includes(p)) {
                        continue;
                    }
                    lt |= p < protocol;
                    eq |= p == protocol;
                    gt |= p > protocol;
                    if (lt && eq && gt) {
                        break;
                    }
                }
                return new Match(lt, eq, gt, false);
            }
        }

        @Override
        public Order compare(ClassificationRequest request) {
            if (request.getProtocol() != null) {
                var p = request.getProtocol().getDecimal();
                return p < protocol ? Order.LT : p == protocol ? Order.EQ : Order.GT;
            } else {
                return Order.NA;
            }
        }

    }

    @ToString(onlyExplicitlyIncluded = true)
    public abstract static class Port extends Threshold<Integer> {
        @ToString.Include protected final int port;
        private final Function<PreprocessedRule, PortValue> getRulePort;
        private final Function<ClassificationRequest, Integer> getRequestPort;

        public Port(
                Function<Bounds, Bound<Integer>> getBound,
                BiFunction<Bounds, Bound<Integer>, Bounds> setBound,
                int port,
                Function<PreprocessedRule, PortValue> getRulePort,
                Function<ClassificationRequest, Integer> getRequestPort
        ) {
            super(getBound, setBound);
            this.port = port;
            this.getRulePort = getRulePort;
            this.getRequestPort = getRequestPort;
        }

        @Override
        public final Integer getThreshold() {
            return port;
        }

        @Override
        protected final Match match(PreprocessedRule rule, Bounds bounds) {
            var portValue = getRulePort.apply(rule);
            if (portValue == null) {
                return Match.NA;
            } else {
                var bound = getBound.apply(bounds);
                var lt = false;
                var eq = false;
                var gt = false;
                for (var range : portValue.getPortRanges()) {
                    if (!bound.overlaps(range.getBegin(), range.getEnd())) {
                        continue;
                    }
                    lt |= range.getBegin() < port;
                    eq |= range.contains(port);
                    gt |= range.getEnd() > port;
                    if (lt && eq && gt) {
                        break;
                    }
                }
                return new Match(lt, eq, gt, false);
            }
        }

        @Override
        public final Order compare(ClassificationRequest request) {
            var p = getRequestPort.apply(request);
            if (p != null) {
                return p < port ? Order.LT : p == port ? Order.EQ : Order.GT;
            } else {
                return Order.NA;
            }
        }

        // getClass() (not instanceof) is load-bearing: the SrcPort and DstPort subclasses share
        // the port value but must stay distinct in the candidate-threshold Set — an instanceof
        // equals would collapse SrcPort(n) and DstPort(n) and corrupt the decision tree.
        @SuppressWarnings("EqualsGetClass")
        @Override
        public final boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Port port1 = (Port) o;
            return port == port1.port;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(port);
        }
    }

    public static final class SrcPort extends Port {
        public SrcPort(int port) {
            super(
                    bs -> bs.srcPort,
                    (bs, b) -> new Bounds(bs.protocol, b, bs.dstPort, bs.srcAddr, bs.dstAddr),
                    port,
                    pr -> pr.srcPort,
                    ClassificationRequest::getSrcPort
            );
        }
    }

    @ToString
    public static final class DstPort extends Port {
        public DstPort(int port) {
            super(
                    bs -> bs.dstPort,
                    (bs, b) -> new Bounds(bs.protocol, bs.srcPort, b, bs.srcAddr, bs.dstAddr),
                    port,
                    pr -> pr.dstPort,
                    ClassificationRequest::getDstPort
            );
        }
    }

    public abstract static class Address extends Threshold<IpAddr> {
        protected final IpAddr address;
        private final Function<PreprocessedRule, IpValue> getRuleAddress;
        private final Function<ClassificationRequest, IpAddr> getRequestAddress;

        public Address(
                Function<Bounds, Bound<IpAddr>> getBound,
                BiFunction<Bounds, Bound<IpAddr>, Bounds> setBound,
                IpAddr address,
                Function<PreprocessedRule, IpValue> getRuleAddress,
                Function<ClassificationRequest, IpAddr> getRequestAddress
        ) {
            super(getBound, setBound);
            this.address = address;
            this.getRuleAddress = getRuleAddress;
            this.getRequestAddress = getRequestAddress;
        }

        @Override
        public final IpAddr getThreshold() {
            return address;
        }

        @Override
        protected final Match match(PreprocessedRule rule, Bounds bounds) {
            var ipValue = getRuleAddress.apply(rule);
            if (ipValue == null) {
                return Match.NA;
            } else {
                var lt = false;
                var eq = false;
                var gt = false;
                var bound = getBound.apply(bounds);
                for (var range : ipValue.getIpAddressRanges()) {
                    if (!bound.overlaps(range.begin, range.end)) {
                        continue;
                    }
                    lt |= range.begin.compareTo(address) < 0;
                    eq |= range.contains(address);
                    gt |= range.end.compareTo(address) > 0;
                    if (lt && eq && gt) {
                        break;
                    }
                }
                return new Match(lt, eq, gt, false);
            }
        }

        @Override
        public final Order compare(ClassificationRequest request) {
            var s = getRequestAddress.apply(request);
            if (s != null) {
                var c = s.compareTo(address);
                return c < 0 ? Order.LT : c == 0 ? Order.EQ : Order.GT;
            } else {
                return Order.NA;
            }
        }

        // getClass() (not instanceof) is load-bearing: the SrcAddress and DstAddress subclasses
        // share the address value but must stay distinct in the candidate-threshold Set — an
        // instanceof equals would collapse them and corrupt the decision tree.
        @SuppressWarnings("EqualsGetClass")
        @Override
        public final boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Address address1 = (Address) o;
            return address.equals(address1.address);
        }

        @Override
        public final int hashCode() {
            return Objects.hash(address);
        }
    }

    public static final class SrcAddress extends Address {
        public SrcAddress(IpAddr address) {
            super(
                    bs -> bs.srcAddr,
                    (bs, b) -> new Bounds(bs.protocol, bs.srcPort, bs.dstPort, b, bs.dstAddr),
                    address,
                    pr -> pr.srcAddr,
                    ClassificationRequest::getSrcAddress
            );
        }

        @Override
        public String toString() {
            return "SrcAddress{"
                    + "address=" + address
                    + '}';
        }
    }

    public static final class DstAddress extends Address {
        public DstAddress(IpAddr address) {
            super(
                    bs -> bs.dstAddr,
                    (bs, b) -> new Bounds(bs.protocol, bs.srcPort, bs.dstPort, bs.srcAddr, b),
                    address,
                    pr -> pr.dstAddr,
                    ClassificationRequest::getDstAddress
            );
        }

        @Override
        public String toString() {
            return "DstAddress{"
                    + "address=" + address
                    + '}';
        }
    }

}
