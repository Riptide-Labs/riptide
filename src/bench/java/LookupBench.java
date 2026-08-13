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

/**
 * Measures NodeRegistry.lookup cost against node count, and compares it with an
 * inet.ipaddr associative trie doing the same longest-prefix-match.
 *
 * Not JMH. Deliberately simple: the effect under test is orders of magnitude,
 * not percent, so a warmed loop with a consumed result is sufficient to tell
 * O(n) from O(prefix length).
 *
 * Asserted (reference ratio only): the trie's ns/op is scale-independent from
 * 100 to 10,000 entries. Registry numbers and the speedup column are
 * informational; story 1.4 activates the production-lookup budget.
 */
public final class LookupBench {

    /** Measured baseline 2.1 (2026-08-13, M-series laptop); ~3x margin for JIT and machine noise. */
    static final double TRIE_FLATNESS_MAX = 6.0;

    private static final int[] SCALES = {100, 1_000, 5_000, 10_000};
    private static final int WARMUP = 50_000;
    private static final int MEASURE = 200_000;
    // the linear scan costs ~0.5 ms/op at 10k entries; quick mode trims its
    // iteration counts at the large scales so a default run stays usable
    private static final int QUICK_WARMUP = 5_000;
    private static final int QUICK_MEASURE = 20_000;

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

        final Map<Integer, Long> trieNsByScale = new HashMap<>();
        for (final int n : SCALES) {
            trieNsByScale.put(n, run(report, n));
        }

        final long baseline = trieNsByScale.get(100);
        final double flatness = baseline == 0 ? 1.0 : (double) trieNsByScale.get(10_000) / baseline;
        report.assertRatio("lookup.trie-scale-flatness", flatness, TRIE_FLATNESS_MAX);
    }

    /** Runs one scale, records measurements, and returns the reference trie's ns/op. */
    private static long run(final BudgetReport report, final int n) throws Exception {
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

        // --- subject 2: associative trie over the same prefixes
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

        final boolean trim = !report.full() && n >= 5_000;
        final long registryNs = timeRegistry(registry, addrs,
                trim ? QUICK_WARMUP : WARMUP, trim ? QUICK_MEASURE : MEASURE);
        final long trieNs = timeTrie(trie, ipv4);

        System.out.printf("%-8d %14d %14d %9.1fx%n", n, registryNs, trieNs,
                trieNs == 0 ? Double.NaN : (double) registryNs / trieNs);
        report.measure("lookup", "registry-ns-op@" + n, registryNs);
        report.measure("lookup", "trie-ns-op@" + n, trieNs);
        report.measure("lookup", "speedup@" + n, Math.round((double) registryNs / trieNs));
        return trieNs;
    }

    private static long timeRegistry(final NodeRegistry registry, final InetAddress[] addrs,
                                     final int warmup, final int measure) {
        int sink = 0;
        for (int i = 0; i < warmup; i++) {
            sink += registry.lookup(new ExporterIdentity.NetflowIpfix(addrs[i % addrs.length], 0)).isPresent() ? 1 : 0;
        }
        final long start = System.nanoTime();
        for (int i = 0; i < measure; i++) {
            sink += registry.lookup(new ExporterIdentity.NetflowIpfix(addrs[i % addrs.length], 0)).isPresent() ? 1 : 0;
        }
        final long elapsed = System.nanoTime() - start;
        sinkhole = sink;
        return elapsed / measure;
    }

    private static long timeTrie(final IPv4AddressAssociativeTrie<String> trie, final IPv4Address[] addrs) {
        int sink = 0;
        for (int i = 0; i < WARMUP; i++) {
            final var node = trie.longestPrefixMatchNode(addrs[i % addrs.length]);
            sink += node != null ? 1 : 0;
        }
        final long start = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) {
            final var node = trie.longestPrefixMatchNode(addrs[i % addrs.length]);
            sink += node != null ? 1 : 0;
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
