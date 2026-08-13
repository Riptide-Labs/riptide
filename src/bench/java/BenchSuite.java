/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.nio.file.Path;

/**
 * Entry point for {@code make bench}: runs all FR-1 budget harnesses, writes one
 * combined report, and exits nonzero if any ratio assertion fails.
 *
 * <p>{@code --full} (BENCH_FULL=1 via the Makefile) adds the multi-minute Spring-binder
 * sweeps and full-precision registry timings; the default run keeps everything the
 * assertions need and finishes in about a minute.</p>
 */
public final class BenchSuite {

    static final Path REPORT = Path.of("target/bench-report.json");

    private BenchSuite() {
    }

    /**
     * Shared /24 generator so every harness probes an identical address distribution
     * (that is what makes their numbers comparable in one report). Wraps the second
     * octet so scales beyond 65,536 still produce valid addresses.
     */
    static String cidr(final int i) {
        return "10." + ((i / 256) % 256) + "." + (i % 256) + ".0/24";
    }

    public static void main(final String[] args) throws Exception {
        final BudgetReport report = new BudgetReport(BudgetReport.isFull(args));
        LookupBench.run(report);
        ShapeBench.run(report);
        BindBench.run(report);
        System.exit(report.finish(REPORT) ? 0 : 1);
    }
}
