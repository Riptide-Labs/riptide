/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import inet.ipaddr.IPAddressString;
import inet.ipaddr.ipv4.IPv4Address;
import inet.ipaddr.ipv4.IPv4AddressAssociativeTrie;
import org.riptide.node.NodeDefinition;
import org.riptide.node.NodeRegistry;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Measures NodeRegistry.lookup cost against node count, and compares it with an
 * inet.ipaddr associative trie doing the same longest-prefix-match.
 *
 * Not JMH. Deliberately simple: the effect under test is orders of magnitude,
 * not percent, so a warmed loop with a consumed result is sufficient to tell
 * O(n) from O(prefix length).
 *
 * Asserted: the reference trie's ns/op is scale-independent from 100 to 10,000
 * entries, and the production registry path (trie-backed since story 1.4) stays
 * within a small factor of the reference and scale-independent itself. The
 * production overhead over the raw reference is the per-lookup address parse plus
 * Optional/Node wrapping.
 */
public final class LookupBench {

    /** Measured baseline 2.1 (2026-08-13, M-series laptop); ~3x margin for JIT and machine noise. */
    static final double TRIE_FLATNESS_MAX = 6.0;

    /** Measured baseline 2.5 (2026-08-14, M-series laptop, trie-backed registry); ~3x margin. */
    static final double PRODUCTION_VS_REFERENCE_MAX = 8.0;

    /** Measured baseline 1.3 (2026-08-14, M-series laptop); ~3x margin per the README rule. */
    static final double PRODUCTION_FLATNESS_MAX = 4.5;

    private static final int[] SCALES = {100, 1_000, 5_000, 10_000};
    private static final int WARMUP = 50_000;
    private static final int MEASURE = 200_000;

    /** Blackhole: consumed via volatile write so the JIT cannot elide the measured loops. */
    private static volatile int sinkhole;

    private LookupBench() {
    }

    public static void main(final String[] args) throws Exception {
        BudgetReport.standalone("lookup", args, LookupBench::run);
    }

    static void run(final BudgetReport report) throws Exception {
        System.out.printf("java=%s vendor=%s%n", System.getProperty("java.version"), System.getProperty("java.vendor"));
        System.out.printf("%-8s %14s %14s %10s%n", "nodes", "registry ns/op", "trie ns/op", "speedup");

        final Map<Integer, Sample> samples = new HashMap<>();
        for (final int n : SCALES) {
            samples.put(n, run(report, n));
        }

        final double trieFlatness = (double) samples.get(10_000).trieNs() / samples.get(100).trieNs();
        report.assertRatio("lookup.trie-scale-flatness", trieFlatness, TRIE_FLATNESS_MAX);

        // production-lookup budget (FR-4): the registry path must stay within a small
        // factor of the raw reference trie, and independent of entry count
        final double vsReference = (double) samples.get(10_000).registryNs() / samples.get(10_000).trieNs();
        report.assertRatio("lookup.production-vs-reference", vsReference, PRODUCTION_VS_REFERENCE_MAX);

        final double productionFlatness =
                (double) samples.get(10_000).registryNs() / samples.get(100).registryNs();
        report.assertRatio("lookup.production-scale-flatness", productionFlatness, PRODUCTION_FLATNESS_MAX);
    }

    private record Sample(long registryNs, long trieNs) {
    }

    /** Runs one scale, recording measurements and returning both subjects' ns/op. */
    private static Sample run(final BudgetReport report, final int n) throws Exception {
        final List<String> subnets = subnets(n);

        // --- subject 1: NodeRegistry as it exists today
        final Map<String, NodeDefinition> nodes = new HashMap<>();
        for (int i = 0; i < n; i++) {
            final NodeDefinition def = new NodeDefinition();
            def.setSubnetAddress(new IPAddressString(subnets.get(i)));
            nodes.put("node-" + i, def);
        }
        final NodeRegistry registry = new NodeRegistry();
        registry.setNodes(nodes);
        registry.validate();

        // --- subject 2: a raw associative trie over the same prefixes. This is an
        // intentional lower bound (IPv4-only, un-pinned, pre-parsed probes), so the
        // production-vs-reference ratio includes the wrapper's parse and dispatch cost
        final IPv4AddressAssociativeTrie<String> trie = new IPv4AddressAssociativeTrie<>();
        for (int i = 0; i < n; i++) {
            trie.put(new IPAddressString(subnets.get(i)).getAddress().toIPv4().toPrefixBlock(), "node-" + i);
        }

        // probe addresses spread across the whole configured space, so neither
        // subject is measured only on its best case
        final int probes = 256;
        final InetAddress[] addrs = new InetAddress[probes];
        final IPv4Address[] ipv4 = new IPv4Address[probes];
        for (int i = 0; i < probes; i++) {
            final String base = subnets.get((int) ((long) i * n / probes));
            final String host = base.substring(0, base.indexOf('/')).replaceFirst("0$", "5");
            addrs[i] = InetAddress.getByName(host);
            ipv4[i] = new IPAddressString(host).getAddress().toIPv4();
        }

        final long registryNs = time(i ->
                registry.lookup(new ExporterIdentity.NetflowIpfix(addrs[i % addrs.length], 0)).isPresent() ? 1 : 0);
        final long trieNs = time(i ->
                trie.longestPrefixMatchNode(ipv4[i % ipv4.length]) != null ? 1 : 0);
        if (registryNs == 0 || trieNs == 0) {
            // a zero measurement means the harness broke (elided loop, clock trouble);
            // fail loudly rather than letting the ratios collapse to a passing value
            throw new AssertionError("0 ns/op measured at n=" + n + ": harness broken");
        }

        final double speedup = (double) registryNs / trieNs;
        System.out.printf("%-8d %14d %14d %9.1fx%n", n, registryNs, trieNs, speedup);
        report.measure("lookup", "registry-ns-op@" + n, registryNs);
        report.measure("lookup", "trie-ns-op@" + n, trieNs);
        report.measure("lookup", "speedup@" + n, Math.round(speedup));
        return new Sample(registryNs, trieNs);
    }

    /**
     * One timing discipline for both subjects: identical warmup and iteration counts,
     * sink consumed through the volatile blackhole. The probe returns the value to
     * accumulate for iteration {@code i}.
     */
    private static long time(final IntUnaryOperator probe) {
        int sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            sink += probe.applyAsInt(i);
        }
        final long start = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) {
            sink += probe.applyAsInt(i);
        }
        final long elapsed = System.nanoTime() - start;
        sinkhole = sink;
        return elapsed / MEASURE;
    }

    private static List<String> subnets(final int n) {
        final List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(BenchSuite.cidr(i));
        }
        return out;
    }
}
