---
sidebar_position: 6
title: Write a classification rule
description: Write a ruleset CSV, point riptide at it, reload it without a restart, and confirm every rule loaded.
---

# Write a classification rule

Classification names a flow's `application` from a CSV ruleset when the exporter did not name it itself.

## Prerequisites

- A running collector, or the jar and a reachable ClickHouse to start one against.
- Write access to the file the collector will read, or an `http(s)://` endpoint that serves it.

## Steps

1. Write the ruleset. The header is fixed and every column must be present, in this order:

   ```csv
   name;protocol;srcAddress;srcPort;dstAddress;dstPort;exporterFilter;omnidirectional
   ssh;tcp;;;;22;;true
   dns;tcp,udp;;;;53;;true
   mgmt;;10.0.0.0/8;;;;;false
   ```

   Row order is the evaluation priority: the earliest matching row wins, in both directions of an omnidirectional rule.
   Put specific rules (address plus port) above broad ones (port-only).

   An empty condition column means "any"; there is no wildcard.
   A column that is filled in but names nothing riptide can resolve gets the whole rule rejected: it classifies nothing, and the load log names it.
   That covers a typo (`tpc`), a stray `,`, a `*`, and a protocol written as a number.
   Name protocols by keyword, not by number: `tcp`, not `6`.
   The keywords are IANA's, with two riptide still accepts under the older name: 55 is `MOBILE` (IANA renamed it `Min-IPv4`) and 84 accepts `TTP` as well as `IPTM`.
   One bad keyword refuses the whole rule, so `tcp,tpc` is refused rather than quietly narrowed to `tcp`.

   Leave `exporterFilter` empty.
   The column is part of the required header and cannot be removed, but nothing evaluates a value in it, so a rule carrying one is rejected rather than silently applied to every exporter.
   Per-exporter scoping does not exist today.

2. Point the collector at the file:

   ```properties
   riptide.classification.rules=file:/etc/riptide/classification-rules.csv
   ```

   The default is the bundled `classpath:classification-rules.csv`.
   Any Spring resource location works, including `https://rules.internal/riptide.csv`, which is how one ruleset serves a fleet without shipping a file to every host.
   A remote ruleset is fetched eagerly at startup, so a rules server that is down usually keeps the collector from coming up, and nothing authenticates the fetch, so protect the endpoint at the network layer.

3. To apply edits without a restart, set a reload interval:

   ```properties
   riptide.classification.reload-interval=5m
   ```

   Absent or `0` is the default: the resource is parsed once while the context starts, and nothing re-reads it.
   With an interval, the resource is polled on that schedule; unchanged bytes rebuild nothing, and a fetch that fails or a ruleset that will not parse keeps the last good rules classifying.
   Keep the interval above the build time your ruleset needs, see [Classification tree build cost](../architecture/classification-build-cost.md#supported-ruleset-size).
   The full reload semantics are in [How configuration reloads work](../architecture/reloading.md).

4. Verify the load. Start the collector (or wait one interval) and read the log:

   ```bash
   grep -A3 'calculated flow classification decision tree' riptide.log
   grep 'Classification rules from' riptide.log
   ```

   Expected output, for the three-rule file above:

   ```text
   2026-09-23T14:37:01.762+02:00  INFO 17186 --- [ificationEngine] o.r.c.i.DefaultClassificationEngine      : calculated flow classification decision tree
   time (ms): 48
   rules    : 3 (including reversed rules: 5)
   leaves   : 7
   2026-09-23T14:37:02.049+02:00  INFO 17186 --- [           main] o.r.c.i.ClassificationRuleReloader       : Classification rules from URL [file:/etc/riptide/classification-rules.csv] published: 3 rules, none rejected
   ```

   `rules` counts the CSV rows; the reversed count adds one per omnidirectional rule that carries a port or address condition.

5. Check that nothing was rejected. A rejected rule is not a failed reload: the rest of the ruleset keeps serving and the reload counters read healthy. The one series that says part of your edit classifies nothing is `classification.rules.rejected`:

   ```bash
   curl -s http://localhost:8080/metrics | grep '^classification_rules_'
   ```

   Expected output:

   ```text
   classification_rules_preprocessed 5.0
   classification_rules_published 3.0
   classification_rules_rejected 0.0
   ```

   With a rule whose protocol column reads `tpc`, the load log names it instead:

   ```text
   2026-09-23T14:37:01.702+02:00 ERROR 17188 --- [ificationEngine] o.r.c.i.DefaultClassificationEngine      : Rule DefaultRule(name=ssh, dstAddress=null, dstPort=22, srcPort=null, srcAddress=null, protocol=tpc, exporterFilter=null, groupPosition=0, position=0, omnidirectional=true) is not valid. Ignoring rule.

   java.lang.IllegalArgumentException: protocol names 'tpc', which riptide cannot resolve. A rule naming a protocol it does not know would be applied to every protocol rather than the one it names, so it is refused. Name a protocol by keyword, not by number, and leave the column empty to mean any protocol.
   ...
   2026-09-23T14:37:02.039+02:00  WARN 17188 --- [           main] o.r.c.i.ClassificationRuleReloader       : Classification rules from URL [file:/etc/riptide/classification-rules.csv] published: 2 rules, of which 1 were rejected and classify nothing: ssh
   ```

   The WARN names the rule; the ERROR beside it names the column and the offending value.
   Alert on `classification_rules_rejected > 0` at `/metrics`.

## Related

- [Enrichment reference](../reference/enrichment.md#classification) for the two keys.
- [Enrichment](../architecture/enrichment.md#classification) for where the rules sit in the application ladder.
- [How configuration reloads work](../architecture/reloading.md) for what a failed fetch, a 404 and an empty body each do.
- [Metrics reference](../reference/metrics.md) for the `classification.*` family.

The outputs above were captured from the 0.15.0 jar with the file path substituted; a remote `https://` ruleset was not exercised.
