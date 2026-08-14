# FR-1 budget benchmarks

Standalone harnesses (outside the Maven source roots, deliberately) that guard riptide's scale budgets.
Originally built for the research behind the 0.9 milestone; promoted to a repeatable make target with ratio assertions in story 1.2.

## Running

```sh
make bench              # quick mode, ~1 minute: everything the assertions need
make bench BENCH_FULL=1 # full sweep, many minutes: adds the 5k/10k Spring-binder rows
                        # and full-precision registry timings (for re-baselining)
```

The suite prints human tables, writes `target/bench-report.json`, and exits nonzero if any assertion fails, naming the measured and required values.
Not part of `make jar`; never a build gate.

| Harness | Measures |
|---|---|
| `LookupBench` | `NodeRegistry.lookup` linear scan vs `inet.ipaddr` associative trie, by node count |
| `BindBench` | Spring `Binder` cost for `riptide.nodes`, inline (6 keys/node) vs minimal (1 key/node) |
| `ShapeBench` | Binder cost by keys-per-node at 10k nodes (full mode), plus direct SnakeYAML parse of the 3-key profile shape |
| `BenchSuite` | Entry point: runs all three into one report |

Each harness also runs standalone (same classpath, its own `main`); a standalone run writes a partial report to `target/bench-report-<name>.json` and never touches the combined suite report.

## What is asserted, and what is not

Only **ratios of measurements taken within the same run** are ever asserted; absolute numbers are informational.
A slow machine scales both sides of a ratio equally, so the gates are machine-independent and survive noisy runners.

Asserted today:

| Assertion | Meaning | Threshold | Baseline (M-series laptop) |
|---|---|---|---|
| `lookup.trie-scale-flatness` | Reference trie ns/op at 10k entries vs at 100 entries | ≤ 6.0 | 2.1 (2026-08-13) |
| `lookup.production-vs-reference` | Production `NodeRegistry.lookup` ns/op vs reference trie ns/op at 10k entries | ≤ 8.0 | 2.5 (2026-08-14) |
| `lookup.production-scale-flatness` | Production `NodeRegistry.lookup` ns/op at 10k entries vs at 100 entries | ≤ 4.5 | 1.3 (2026-08-14) |
| `parse.direct-linearity` | Direct-parse per-entry cost at 100k entries vs at 10k | ≤ 3.0 | 0.7 (2026-08-13) |
| `parse.production-vs-raw` | Production `InventoryLoader` (parse + validate + resolve + trie build) vs raw SnakeYAML load at 10k entries | ≤ 4.0 | 1.3 (2026-08-14) |

The first assertion (`lookup.trie-scale-flatness`) is a reference-implementation property (a failure means the harness or environment broke).
The two production-lookup assertions (`lookup.production-vs-reference`, `lookup.production-scale-flatness`) are the FR-4 budget: they fail when a change makes exporter matching scale with inventory size again.
The fourth assertion (`parse.direct-linearity`) guards direct YAML parse linearity across scales.
The fifth (`parse.production-vs-raw`) is the FR-5 budget: it fails when loader overhead (validation, reference resolution, trie build) stops being a small factor over the raw parse.

Deliberately **not** asserted:

- Binder-vs-direct-parse ratio: the production parse path is story 2.1's budget.
- Any absolute ns/ms value: machine-dependent, would make the gate flaky, and a flaky gate has no authority.

## Budget activation model

Production budgets activate with the story that lands each production path; no assertion ever ships expected-fail:

- **Story 1.4** (trie matching) activated the production-lookup budget: see the table above.
- **Story 2.1** (direct-parse inventory) activated the production-parse budget: see the table above. For SM-1 context the absolute load time is recorded informationally (`inventory-loader-ms@10000`, ~51 ms measured, against the 302,597 ms Spring-binder baseline the story replaced).
- Hot reload (story 2.2) shares `InventoryLoader.parse` with boot, so the parse budget covers both paths; each cycle additionally pays the byte read and content hash, and changed content pays a strict UTF-8 decode before the parse.

Adding a budget is a `report.measure(...)` + `report.assertRatio(name, measured, max)` pair in the harness that exercises the path.

## Re-baselining

When a threshold needs re-deriving (new hardware class, intentional reference change):

1. Run `make bench BENCH_FULL=1` on an idle machine (check `uptime`); repeat 3 times.
2. Take the worst (highest) measured ratio across runs as the new baseline.
3. Set the threshold at roughly 3x the baseline and record both in the constant's javadoc (`TRIE_FLATNESS_MAX`, `PRODUCTION_VS_REFERENCE_MAX`, `PRODUCTION_FLATNESS_MAX`, `DIRECT_LINEARITY_MAX`, `PRODUCTION_VS_RAW_MAX`) and in the table above.
4. Never tighten a threshold in the same change that alters what the harness measures.

## Report

`target/bench-report.json` carries the measurements, the assertion outcomes, and the `restart-enrichment-warmup` field: the SM-C3 counter-metric slot, informational and never asserted, `not-yet-measured` until the OQ-3 fleet-scale walk measurement exists.

Caveats: single run, no variance tracking; treat absolute output as orders of magnitude.
For statistics-grade numbers on committed hot paths, use the JMH suite (`make bench-jmh`).
