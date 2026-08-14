/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import inet.ipaddr.IPAddressString;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.CredentialVersion;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.PollingProfile;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.node.NodeDefinition;
import org.riptide.node.NodeRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers two questions about the "one explicit entry per exporter" shape:
 *
 *   1. How does Spring Binder cost scale with keys-per-node at 10k nodes?
 *      (full mode only; the sweep takes minutes)
 *   2. What does the same content cost when parsed directly (SnakeYAML -> setters),
 *      bypassing the property binder entirely?
 *
 * The proposed shape is 3 keys/node: subnet-address + credentials + polling.
 *
 * Asserted (reference ratio only): the direct parse stays linear from 10k to 100k
 * entries. Binder numbers are informational; story 2.1 activated the
 * production-parse budget.
 */
public final class ShapeBench {

    /** Measured baseline 0.66 (2026-08-13, M-series laptop); generous margin for GC and machine noise. */
    static final double DIRECT_LINEARITY_MAX = 3.0;

    /** Measured baseline 1.3 (2026-08-14, M-series laptop); ~3x margin per the README rule. */
    static final double PRODUCTION_VS_RAW_MAX = 4.0;

    private ShapeBench() {
    }

    public static void main(final String[] args) throws Exception {
        BudgetReport.standalone("shape", args, ShapeBench::run);
    }

    static void run(final BudgetReport report) {
        System.out.printf("%njava=%s snakeyaml=%s%n",
                System.getProperty("java.version"), Yaml.class.getPackage().getImplementationVersion());

        if (report.full()) {
            System.out.println("\n=== Spring Binder, 10,000 nodes, by keys per node ===");
            System.out.printf("%-28s %8s %10s %12s%n", "shape", "keys/node", "total keys", "bind ms");
            bindShape(report, "subnet only", 10_000, 1);
            bindShape(report, "+ credentials ref", 10_000, 2);
            bindShape(report, "+ polling ref  (PROPOSED)", 10_000, 3);
            bindShape(report, "+ observation-domain", 10_000, 4);
            bindShape(report, "inline snmp block (today)", 10_000, 6);
        }

        System.out.println("\n=== Direct parse (SnakeYAML -> setters), proposed 3-key shape ===");
        System.out.printf("%-12s %12s %12s %12s%n", "nodes", "yaml ms", "build ms", "total ms");
        final Map<Integer, Long> totalNsByScale = new LinkedHashMap<>();
        for (final int n : new int[]{1_000, 10_000, 50_000, 100_000}) {
            totalNsByScale.put(n, directParse(report, n));
        }

        final double perEntry10k = (double) totalNsByScale.get(10_000) / 10_000;
        final double perEntry100k = (double) totalNsByScale.get(100_000) / 100_000;
        final double linearity = perEntry10k == 0 ? 1.0 : perEntry100k / perEntry10k;
        report.assertRatio("parse.direct-linearity", linearity, DIRECT_LINEARITY_MAX);

        productionLoader(report);
    }

    // ------------------------------------------------- Production loader (FR-5)

    /**
     * The production-parse budget: InventoryLoader end-to-end (parse + validate +
     * reference resolution + trie build) vs a raw SnakeYAML load of the same
     * content, same run.
     */
    private static void productionLoader(final BudgetReport report) {
        System.out.println("\n=== Production InventoryLoader vs raw parse, 10,000 agent ranges ===");
        System.out.printf("%-12s %12s %12s %8s%n", "entries", "raw ms", "loader ms", "ratio");

        final SnmpProfilesConfig profiles = new SnmpProfilesConfig(
                Map.of("corp-v3", benchCredentials()),
                Map.of("default", new PollingProfile()));
        final String inventory = generateInventoryYaml(10_000);

        // warm both paths once so neither first-run pays class loading alone
        rawLoad(inventory);
        InventoryLoader.parse(profiles, inventory, "bench.yaml");

        // best of three paired runs: a GC pause in one window neither trips nor
        // masks the gate; a real regression raises all three ratios
        double bestRatio = Double.MAX_VALUE;
        long bestRawNs = 0;
        long bestLoaderNs = 0;
        for (int i = 0; i < 3; i++) {
            final long t0 = System.nanoTime();
            rawLoad(inventory);
            final long t1 = System.nanoTime();
            InventoryLoader.parse(profiles, inventory, "bench.yaml");
            final long t2 = System.nanoTime();
            final long rawNs = t1 - t0;
            final long loaderNs = t2 - t1;
            if (rawNs == 0 || loaderNs == 0) {
                throw new AssertionError("0 ns measured for the parse budget: harness broken");
            }
            final double ratio = (double) loaderNs / rawNs;
            if (ratio < bestRatio) {
                bestRatio = ratio;
                bestRawNs = rawNs;
                bestLoaderNs = loaderNs;
            }
        }
        System.out.printf("%-12d %12d %12d %7.1fx%n",
                10_000, bestRawNs / 1_000_000, bestLoaderNs / 1_000_000, bestRatio);
        report.measure("parse", "inventory-raw-ms@10000", bestRawNs / 1_000_000);
        report.measure("parse", "inventory-loader-ms@10000", bestLoaderNs / 1_000_000);
        report.assertRatio("parse.production-vs-raw", bestRatio, PRODUCTION_VS_RAW_MAX);
    }

    // deliberate local copy of TestCredentials.v3(): the bench source set cannot
    // see test roots; update alongside the shared fixture when validation tightens
    private static CredentialSet benchCredentials() {
        final CredentialSet set = new CredentialSet();
        set.setVersion(CredentialVersion.V3);
        set.setSecurityName("bench");
        return set;
    }

    private static void rawLoad(final String content) {
        final LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(Integer.MAX_VALUE);
        final Object root = new Yaml(opts).load(content);
        if (root == null) {
            throw new AssertionError("nothing parsed");
        }
    }

    private static String generateInventoryYaml(final int n) {
        final StringBuilder sb = new StringBuilder(n * 90);
        sb.append("riptide:\n  snmp:\n    agents:\n");
        for (int i = 0; i < n; i++) {
            sb.append("      \"").append(BenchSuite.cidr(i)).append("\":\n")
              .append("        credentials: corp-v3\n")
              .append("        polling: default\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Spring

    private static void bindShape(final BudgetReport report, final String label, final int n, final int keysPerNode) {
        final Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            final String p = "riptide.nodes.node-" + i + ".";
            props.put(p + "subnet-address", BenchSuite.cidr(i));
            if (keysPerNode >= 2) {
                props.put(p + "snmp.snmp-version", "v2c");           // stands in for a credentials ref
            }
            if (keysPerNode >= 3) {
                props.put(p + "snmp.timeout", 500);                  // stands in for a polling ref
            }
            if (keysPerNode >= 4) {
                props.put(p + "observation-domain", i);
            }
            if (keysPerNode >= 6) {
                props.put(p + "snmp.retries", 1);
                props.put(p + "snmp.port", 161);
            }
        }
        final Binder binder = new Binder(new MapConfigurationPropertySource(props));
        final long start = System.nanoTime();
        final NodeRegistry bound = binder.bind("riptide", Bindable.of(NodeRegistry.class)).get();
        final long ms = (System.nanoTime() - start) / 1_000_000;
        if (bound.getNodes().size() != n) {
            throw new AssertionError("bound " + bound.getNodes().size());
        }
        System.out.printf("%-28s %8d %10d %12d%n", label, keysPerNode, props.size(), ms);
        report.measure("bind-shape", "keys-" + keysPerNode + "-ms@" + n, ms);
    }

    // ---------------------------------------------------------- Direct parse

    /** Parses n entries, records measurements, and returns the total time in nanoseconds. */
    private static long directParse(final BudgetReport report, final int n) {
        final String yaml = generateYaml(n);

        final LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(Integer.MAX_VALUE);          // default 3 MB is too small at 100k
        final Yaml parser = new Yaml(opts);

        final long t0 = System.nanoTime();
        final Map<String, Object> root = parser.load(yaml);
        final long t1 = System.nanoTime();

        @SuppressWarnings("unchecked")
        final Map<String, Map<String, Object>> entries =
                (Map<String, Map<String, Object>>) ((Map<String, Object>) root.get("riptide")).get("nodes");

        final Map<String, NodeDefinition> built = new LinkedHashMap<>(entries.size() * 2);
        for (final Map.Entry<String, Map<String, Object>> e : entries.entrySet()) {
            final NodeDefinition def = new NodeDefinition();
            def.setSubnetAddress(new IPAddressString((String) e.getValue().get("subnet-address")));
            // credentials / polling would be String refs resolved after the parse;
            // reading them is the representative cost here
            final Object ignoredCreds = e.getValue().get("credentials");
            final Object ignoredPoll = e.getValue().get("polling");
            if (ignoredCreds == null || ignoredPoll == null) {
                throw new AssertionError("missing ref");
            }
            built.put(e.getKey(), def);
        }
        final long t2 = System.nanoTime();

        if (built.size() != n) {
            throw new AssertionError("built " + built.size());
        }
        System.out.printf("%-12d %12d %12d %12d%n", n,
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t2 - t0) / 1_000_000);
        report.measure("parse", "yaml-ms@" + n, (t1 - t0) / 1_000_000);
        report.measure("parse", "build-ms@" + n, (t2 - t1) / 1_000_000);
        report.measure("parse", "total-ms@" + n, (t2 - t0) / 1_000_000);
        return t2 - t0;
    }

    private static String generateYaml(final int n) {
        final StringBuilder sb = new StringBuilder(n * 90);
        sb.append("riptide:\n  nodes:\n");
        for (int i = 0; i < n; i++) {
            sb.append("    node-").append(i).append(":\n")
              .append("      subnet-address: ").append(BenchSuite.cidr(i)).append('\n')
              .append("      credentials: corp-v3\n")
              .append("      polling: default\n");
        }
        return sb.toString();
    }

}
