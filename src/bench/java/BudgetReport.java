/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects benchmark measurements and ratio assertions, prints a human summary and
 * writes a machine-readable report.
 *
 * <p>Absolute numbers are informational only. The only thing ever asserted is a ratio
 * of two measurements taken within the same run, so budgets stay machine-independent:
 * a slow laptop scales both sides of the ratio equally. A failed assertion makes the
 * run exit nonzero naming the measured and the required value.</p>
 *
 * <p>The {@code restart-enrichment-warmup} field is the SM-C3 counter-metric slot:
 * informational, never asserted, {@code not-yet-measured} until the OQ-3 measurement
 * exists.</p>
 */
final class BudgetReport {

    private final boolean full;
    private final Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
    private final List<Assertion> assertions = new ArrayList<>();

    private record Assertion(String name, double measured, double max) {
        boolean pass() {
            return this.measured <= this.max;
        }
    }

    /** One harness's entry point body, shared by the standalone {@code main()}s. */
    @FunctionalInterface
    interface Harness {
        void run(BudgetReport report) throws Exception;
    }

    BudgetReport(final boolean full) {
        this.full = full;
    }

    /**
     * Lifecycle for a harness run on its own: a standalone run writes a partial,
     * harness-named report so it never clobbers the combined suite report.
     */
    static void standalone(final String name, final String[] args, final Harness harness) throws Exception {
        final BudgetReport report = new BudgetReport(isFull(args));
        harness.run(report);
        System.exit(report.finish(Path.of("target/bench-report-" + name + ".json")) ? 0 : 1);
    }

    static boolean isFull(final String[] args) {
        for (final String arg : args) {
            if ("--full".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    boolean full() {
        return this.full;
    }

    void measure(final String section, final String key, final Object value) {
        this.sections.computeIfAbsent(section, s -> new LinkedHashMap<>()).put(key, value);
    }

    void assertRatio(final String name, final double measured, final double max) {
        this.assertions.add(new Assertion(name, measured, max));
    }

    /**
     * Prints the assertion summary, writes the JSON report, and returns whether every
     * assertion passed. Callers turn {@code false} into a nonzero exit.
     */
    boolean finish(final Path jsonPath) throws IOException {
        boolean ok = true;
        System.out.println();
        for (final Assertion assertion : this.assertions) {
            ok &= assertion.pass();
            // Locale.ROOT so the decimal separator is stable across machines
            System.out.printf(Locale.ROOT, "[%s] %s measured=%.2f required<=%.2f%n",
                    assertion.pass() ? "PASS" : "FAIL", assertion.name(), assertion.measured(), assertion.max());
        }
        if (jsonPath.getParent() != null) {
            Files.createDirectories(jsonPath.getParent());
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), document());
        System.out.println("report: " + jsonPath);
        return ok;
    }

    private Map<String, Object> document() {
        final Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("generated", Instant.now().toString());
        doc.put("java", System.getProperty("java.version"));
        doc.put("mode", this.full ? "full" : "quick");
        doc.put("restart-enrichment-warmup", "not-yet-measured");
        doc.put("measurements", this.sections);
        final List<Map<String, Object>> results = new ArrayList<>(this.assertions.size());
        for (final Assertion assertion : this.assertions) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", assertion.name());
            entry.put("measured", assertion.measured());
            entry.put("max", assertion.max());
            entry.put("pass", assertion.pass());
            results.add(entry);
        }
        doc.put("assertions", results);
        return doc;
    }
}
