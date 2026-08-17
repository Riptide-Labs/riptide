/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import inet.ipaddr.IPAddressString;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.PollingProfile;
import org.riptide.inventory.SnmpProfilesConfig;
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
 * entries. Direct-parse numbers are informational; story 2.1 activated the
 * production-parse budget.
 */
public final class ShapeBench {

    /** Stands in for whatever a parsed entry becomes; only the allocation and the address parse are measured. */
    private record ParsedEntry(IPAddressString subnet) {
    }


    /** Measured baseline 0.66 (2026-08-13, M-series laptop); generous margin for GC and machine noise. */
    static final double DIRECT_LINEARITY_MAX = 3.0;

    /** Measured baseline 1.3 (2026-08-17, M-series laptop, re-confirmed after the binder sweep was retired); ~3x margin per the README rule. */
    static final double PRODUCTION_VS_RAW_MAX = 4.0;

    private ShapeBench() {
    }

    public static void main(final String[] args) throws Exception {
        BudgetReport.standalone("shape", args, ShapeBench::run);
    }

    static void run(final BudgetReport report) {
        System.out.printf("%njava=%s snakeyaml=%s%n",
                System.getProperty("java.version"), Yaml.class.getPackage().getImplementationVersion());

        // The Spring-binder shape comparison that used to run here is gone with the shape it
        // compared. It existed to choose between candidate node shapes during design, and it
        // bound the legacy riptide.nodes tree, which 0.9 removed. The decision it informed has
        // shipped; the production loader below is what measures the shape that exists.

        System.out.println("\n=== Direct parse (SnakeYAML -> setters), 3-key entry shape ===");
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
                Map.of("default", PollingProfile.builtInDefault()));
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
        return CredentialSet.usm("bench");
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

        final Map<String, ParsedEntry> built = new LinkedHashMap<>(entries.size() * 2);
        for (final Map.Entry<String, Map<String, Object>> e : entries.entrySet()) {
            // a placeholder rather than a configuration class: this measures the cost of
            // parsing and allocating one object per entry, which is the same whatever the
            // object is, and tying it to a real class made the benchmark break when that
            // class was retired
            final ParsedEntry def = new ParsedEntry(
                    new IPAddressString((String) e.getValue().get("subnet-address")));
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
