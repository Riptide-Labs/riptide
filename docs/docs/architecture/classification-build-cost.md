---
title: Classification tree build cost
sidebar_position: 6
description: The supported ruleset size, the measured build time and work count at four sizes, why the cost grows faster than the ruleset, and what those figures do and do not cover.
---

# Classification tree build cost

The classification engine compiles the ruleset into a decision tree, and that build is the one cost that grows faster than the ruleset does.
At startup the build blocks classification; on a reload it runs beside the rules already serving.
This page publishes the size the build has been measured at and the measurement behind it.
The figures below are quoted by tests in the repository and by a CI check, so the section heading and the numbers in it change only when somebody re-measures.

## Supported ruleset size

Riptide supports classification rulesets of up to 12,500 rules, the largest size the tree build has been run at (12,496 rules), rounded up.
Nothing refuses a larger one: it still loads and still builds, and every rule in it still classifies.
What the bound says is that past this point no measurement backs the cost, and the growth below is steep enough that guessing is a bad idea.

You are told when you cross it.
Every publish that exceeds the bound logs a WARN naming both counts and what it costs you, and `classification.rules.preprocessed` carries the same number for alerting.
Crossing it is a decision, not a fault, so nothing fails and no gauge latches.

The trigger is the preprocessed count, not the row count, because that is what the build works on, and the two differ by up to a factor of two.
So the 12,500 above is the bound for a ruleset shaped like the shipped one, where every rule is omnidirectional; read against 25,000 preprocessed rules it covers both shapes.
A ruleset of 20,000 rules that are not omnidirectional preprocesses to 20,000 and does not warn; 12,600 omnidirectional ones preprocess to 25,200 and do.
Alert on `classification_rules_preprocessed > 25000` if you want the condition rather than the log line.

Expect a build of four to five seconds at the bound.
That is bracketed rather than measured outright, because the only ruleset that size is a synthetic one: building it took 4.43 s, and extending the growth curve from the real shipped ruleset predicts 4.8 s.
The two agree, which is as much confidence as there is to be had without a real ruleset of that size to build.

Count preprocessed rules, not rows.
What the build works on is the preprocessed list, and an omnidirectional rule carrying a port or address condition is built in both directions, so it counts twice.
Every rule in the shipped ruleset is omnidirectional, which is why its 6,248 rows become 12,496.
So the bound is really about 25,000 preprocessed rules: 12,500 omnidirectional rules reach it, and roughly 25,000 one-directional rules reach it too.
You do not have to work out which you have; the engine logs both counts on every load.

At startup the build blocks classification.
On a reload it does not.
Before any ruleset has ever published, a thread that calls into classification waits for the first build to finish, so the figures below are how long after startup classification begins answering.
Once a ruleset has published, a rebuild runs beside it: the previous rules keep classifying, complete, and the build time is how long a rule edit takes to take effect, not a stall.

| Ruleset | Rules | Preprocessed | One `Tree.of` build | maxDepth | avgComp |
| --- | --- | --- | --- | --- | --- |
| **Bundled, the shipped `classification-rules.csv`** | 6,248 | 12,496 | **929 ms** ± 7 | 14 | 14.71 |
| Synthesised ×1 | 6,248 | 12,496 | 921 ms ± 10 | 13 | 14.69 |
| Synthesised ×2 | 12,496 | 24,992 | 4.43 s ± 0.07 | 15 | 16.00 |
| Synthesised ×4 | 24,992 | 49,984 | 24.1 s ± 0.6 | 16 | 17.30 |

Only the bundled row is a real ruleset.
The rest are that same ruleset cloned, with each clone's ports remapped so no two clones share one.
A synthetic ruleset of a given size has different threshold cardinality than a real one of that size would, because real rulesets cluster on well-known ports and a clone spreads evenly.
Take the shape of the growth from those rows, not the seconds.
The ×1 row is what makes that checkable: same rule count, built the synthetic way, 921 ms against the bundled 929 ms, under 1 % apart in time, though not identical in shape (it builds a 13-level tree where the real ruleset builds a 14-level one).
At equal size the synthesis is neither cheap nor dear; whether that still holds at ×4 is exactly what is not known.

The algorithm is quadratic, and the per-unit cost degrades on top of that.
Counting the work the build does, candidates scored times rules at each node, a deterministic quantity that owes nothing to the machine, the core count or the JIT, gives 262,251,844 for the shipped ruleset.
On the synthesised sizes it is 262,290,026 / 1,048,497,458 / 4,192,936,322 at ×1 / ×2 / ×4: ratios of 3.998 and 3.999, an exponent of 2.00.

Two limits on that, because it is the kind of number that invites over-reading.
The ratios come from two doublings of synthesised rulesets, and the clone is built so its distinct-port count scales exactly linearly, so part of that flatness is the construction, not the algorithm.

What that limit can be checked against is the ×1 row, on the same work metric: the real shipped ruleset costs 262,251,844 and the synthesis of the same size costs 262,290,026, 0.015 % apart.
So the clone reproduces what a real ruleset of that size costs the build, which is a stronger agreement than the 1 % the wall times manage.
It does not carry upward: agreement at equal size says nothing about whether the linear port scaling flatters the ×2 and ×4 ratios, and no real ruleset of those sizes exists to check against.
The suite's own ratio check on real rules is not a second control here, because its slices scale their distinct-port count linearly too (199 ports at 200 rules, 397 at 400), so it shares the property rather than testing it.
What that check does is keep the counter honest about tracking the whole quadratic term; it is not independent evidence about the construction.

What the work count does establish is where the extra time comes from.
Work grew ×4.0 per doubling while time grew ×4.81 then ×5.43, so the time spent per unit of work rose about 1.20× and then 1.36×.
That is a cost per unit that worsens with size, not a constant factor, and this change did not measure why.
Allocation and GC at a pinned 4 GB heap, cache behaviour, and scheduling in the common pool are all consistent with it; none was tested.

Doubling the ruleset costs about five times the build, not twice.
Measured: ×4.81 across the first doubling and ×5.43 across the second.
As an exponent that is 2.27 then 2.44, superlinear, and rising rather than constant.
Applying the average of the two to the one real anchor, the 929 ms bundled build, puts ten times the shipped ruleset at roughly three and a half minutes.
Read that as a floor, not an estimate: the timed exponent grows with size, so a single figure understates the cost above ×4.
The work exponent does not grow; what grows is the time each unit of work takes.

The tree gets deeper too, but slowly, about one level per doubling.
Average depth went 11.85 → 12.90 → 13.94 across ×1, ×2 and ×4, and the average comparisons a request costs went 14.69 → 16.00 → 17.30.
So per-flow work does grow with the ruleset, and it grows logarithmically while the build grows superlinearly.
That is the point of the tree, and it is why the build is the cost worth bounding.
The `maxDepth` and `avgComp` columns above come from the same benchmark run as the times; the engine logs the same fields for your ruleset on every load.

What these figures do not cover, five things, each of which would need its own measurement:

- What the work count counts. It is candidates-scored × rules-at-that-node, and nothing else: not the cost inside a verdict, not candidate enumeration or deduplication, not the bounds check, not the classifier sort in a leaf, and, deliberately, not parallelism, since the whole point of the quantity is that it does not vary with core count. So it is the algorithm's dominant term rather than the build's total work, and a change that made every verdict twice as expensive would move the seconds without moving it. Read it as the shape of the growth; read the timed rows for the cost.
- One rule shape. Every measured ruleset consists of rules that constrain a single destination port and nothing else, because that is what the shipped ruleset is and what the clone can reproduce without collisions. A ruleset using address conditions, port ranges, or source-port conditions builds a differently shaped tree and is unmeasured here.
- `Tree.of` only. A reload also reads the resource and runs the preprocess loop, and the benchmark deliberately excludes both. The published number is a lower bound on the reload, not the reload.
- Heap. The benchmark pins `-Xmx4g` so its runs are comparable. How much heap a ruleset at the bound actually needs was not measured, and the tree at ×4 holds about 32,000 nodes and 64,000 leaves.
- CPU. The build scores its split candidates on a parallel stream, so it uses every core the JVM's common pool has for as long as it runs. On a busy collector that is contention with the ingest path, and on a small one it is a longer build.

Nothing here was checked by classifying a flow: the benchmark builds trees and discards them.
What the rows support is that the build completes, in that time, at that size.

Prefer your own number to this table.
The collector already reports it, for your real ruleset on your real hardware, and no interpolation beats that:

- the `calculated flow classification decision tree` INFO line the engine logs after every build, which carries `time (ms)`, `rules` (with the reversed count beside it), `nodes`, `maxDepth` and `avgComp`, the same fields as the table above;
- the `reload` timer in the metrics registry, which spans the whole reload (resource read, preprocessing and build) and so is the number this table is only a lower bound on.

Mind the build against `riptide.classification.reload-interval`.
A poll that finds changed bytes while a build is still running cancels that build and starts again from the new bytes.
Unchanged bytes rebuild nothing, so a short interval is harmless on its own, but a ruleset being rewritten repeatedly, on an interval shorter than the build takes, can keep pre-empting itself and never publish.
At the bound the build is around 4.4 s, so keep the interval comfortably above it if your rules source changes often.

Provenance.
Wall-time figures measured on 2026-09-06 at commit `227a4011`; the work counts were added later (#768) and measured on 2026-09-07 on the same machine, by `make bench-jmh BENCH_TARGET=TreeBuildBenchmark` (source: `src/test/java/org/riptide/benchmarks/classification/TreeBuildBenchmark.java`), on a 10-core Apple M1 Max laptop with JDK 25 and `-Xmx4g`.
The sample size comes from that target's default `BENCH_OPTS` (`-wi 3 -i 10 -f 2`: 2 forks, 3 warmup and 10 measured single shots each, so 20 samples per row), not from the annotations on the class, which are lighter; running the class straight from an IDE gives a much smaller sample.
The `±` is JMH's 99.9 % confidence interval.
The benchmark is not part of any build gate, so these numbers only change when somebody deliberately re-measures.
Two caveats on transferring them: the parallel build makes them core-count dependent, and the reported score is a JIT-warmed build rather than the genuinely cold first build a boot performs.
The run prints its cold shot per fork as `# Warmup Iteration 1`; that came out about 20 % above the warm score at the bundled size and slightly below it at ×2 and ×4.

## Related

- [How configuration reloads work](reloading.md#classification-rule-reloads): what the build blocks and when.
- [Write a classification rule](../guides/classification-rules.md): the ruleset the build consumes.
- [Metrics reference](../reference/metrics.md#classification-rules): `classification.rules.preprocessed` and the `reload` timer.
