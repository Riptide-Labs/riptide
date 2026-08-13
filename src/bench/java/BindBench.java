/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.riptide.node.NodeRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measures Spring Boot 4.1 @ConfigurationProperties binding cost for the riptide.nodes
 * map, at inventory scale.
 *
 * Two shapes per node count:
 *   "inline"  — every node carries its own snmp block  (6 keys/node), today's model
 *   "ref"     — every node carries subnet-address only (1 key/node), the floor a
 *               profile reference would approach
 *
 * The gap between them is the binding cost profiles would actually remove.
 *
 * Fully informational: nothing here is asserted; it documents the motivation for
 * FR-5. Quick mode stops at 1,000 nodes because the 10k inline bind alone takes
 * minutes; BENCH_FULL=1 runs the whole sweep.
 */
public final class BindBench {

    private BindBench() {
    }

    public static void main(final String[] args) throws Exception {
        BudgetReport.standalone("bind", args, BindBench::run);
    }

    static void run(final BudgetReport report) {
        System.out.printf("%njava=%s spring-binder=%s%n",
                System.getProperty("java.version"), Binder.class.getPackage().getImplementationVersion());
        System.out.printf("%-8s %10s %12s %10s %12s %8s%n",
                "nodes", "inline ms", "inline keys", "ref ms", "ref keys", "ratio");

        // warm the binder itself so the first real row is not measuring class loading
        bind(source(200, true));
        bind(source(200, false));

        final int[] scales = report.full()
                ? new int[]{100, 1_000, 5_000, 10_000}
                : new int[]{100, 1_000};
        for (final int n : scales) {
            final var inlineSrc = source(n, true);
            final var refSrc = source(n, false);
            final long inline = bind(inlineSrc);
            final long ref = bind(refSrc);
            System.out.printf("%-8d %10d %12d %10d %12d %7.1fx%n",
                    n, inline, n * 6, ref, n, ref == 0 ? Double.NaN : (double) inline / ref);
            report.measure("bind", "inline-ms@" + n, inline);
            report.measure("bind", "ref-ms@" + n, ref);
        }
    }

    private static long bind(final MapConfigurationPropertySource source) {
        final Binder binder = new Binder(source);
        final long start = System.nanoTime();
        final NodeRegistry bound = binder.bind("riptide", Bindable.of(NodeRegistry.class)).get();
        final long elapsed = System.nanoTime() - start;
        if (bound.getNodes().isEmpty()) {
            throw new AssertionError("nothing bound");
        }
        return elapsed / 1_000_000;
    }

    private static MapConfigurationPropertySource source(final int n, final boolean inline) {
        final Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            final String p = "riptide.nodes.node-" + i + ".";
            props.put(p + "subnet-address", BenchSuite.cidr(i));
            if (inline) {
                props.put(p + "observation-domain", i);
                props.put(p + "snmp.snmp-version", "v2c");
                props.put(p + "snmp.timeout", 500);
                props.put(p + "snmp.retries", 1);
                props.put(p + "snmp.port", 161);
            }
        }
        return new MapConfigurationPropertySource(props);
    }
}
