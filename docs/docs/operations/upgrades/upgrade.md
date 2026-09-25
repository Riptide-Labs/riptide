---
sidebar_position: 1
title: Upgrade riptide
description: Replace the image, jar or package, know which configuration keys fail startup, what the schema check migrates on its own, and what changes in stored data and metrics between releases.
---

# Upgrade riptide

## Prerequisites

- Read the [per-release notes](#what-changes-between-releases) below for every release between yours and the target.
- A deployment in validate mode (`riptide.clickhouse.manage-schema=false`) has the admin credential for `riptide onboard` at hand; see [Onboard a tenant](../tenants/onboard-a-tenant.md).

## Steps

1. Replace the running version.

   | Deployment | Command |
   | --- | --- |
   | Compose | `docker compose pull && docker compose up -d` |
   | Plain jar | replace the jar, restart |
   | deb and rpm | `apt upgrade riptide` or `dnf upgrade riptide`; see [Linux packages](../../guides/linux-packages.md) |
   | NixOS | bump the flake input; see [NixOS](../../guides/nixos.md) |

2. Read the startup log for refused configuration.

   Configuration is backward-compatible within a minor line.
   Breaking configuration moves are impossible to miss: removed trees fail startup (`riptide.nodes` and the retired fleet poll keys), and superseded-but-harmless ones log an explicit error and are ignored (`riptide.snmp.config.definitions`).
   The 0.9 flag day is the big one: any surviving `riptide.nodes` key, in any spelling including the `RIPTIDE_NODES_*` environment form, stops the collector with an error naming the key and the converter.
   Plan it as a migration step, not as a log-review item: under systemd or Kubernetes a missed key means a restart loop until the configuration is converted with [Upgrading from 0.8](upgrading-from-0.8.md).

3. Let the schema check run, and re-onboard where it asks you to; see [What the schema check migrates](#what-the-schema-check-migrates).

4. Restart the collector after any `riptide onboard` run.

   Which rollups are usable is decided once, at startup, so a collector that declined them keeps answering from raw `flows` until it restarts; see [Rollups](../../architecture/rollups.md).

## Verify

```bash
curl -s http://localhost:8080/readyz
```

Expected output:

```text
ok: receivers listening
```

Then confirm the rule gauges have published and no reload is stale:

```bash
curl -s http://localhost:8080/metrics | grep -E '^(classification_rules|classification_reload_stale|config_reload_stale)'
```

Expected output:

```text
classification_reload_stale 0.0
classification_rules_preprocessed 12496.0
classification_rules_published 6248.0
classification_rules_rejected 0.0
```

`config_reload_stale` appears only with hot reload enabled.

## What the schema check migrates

In manage mode (`riptide.clickhouse.manage-schema=true`, the default) riptide ensures its `flows` table with `CREATE TABLE IF NOT EXISTS` at startup, so an existing table is not replaced and flow data survives a restart.
A new column is added in place with `ALTER TABLE … ADD COLUMN IF NOT EXISTS`.
In validate mode (`manage-schema=false`) the collector issues no DDL: the startup column check fails fast naming the missing column, and re-running `riptide onboard` adds it.

:::warning[Non-additive schema changes are not migrated]

Only additive changes are applied in place.
Any other schema change between riptide versions is not applied: the startup column check fails fast when the on-disk schema is stale, and the operator must drop the `flows` table (riptide recreates it in manage mode) or re-provision it in validate mode.
Plan retention accordingly if a release requires dropping the table.

:::

## What changes between releases

### 0.7.0: `sessionCount` means exporters, not templates

`parsers.<name>.sessionCount` used to report the template total, so it overstated by however many templates each exporter announces.
It now reports `(session, observation domain)` pairs.
Expect the value to drop on upgrade, by roughly the templates-per-exporter factor.
The previous quantity is still available under the name that describes it, `parsers.<name>.templateCount`.
This changes what a metric means, not any configuration key; see [Where flows can be lost](../../architecture/loss-accounting.md#parser-gauges-exporters-and-templates) before relying on `sessionCount`.

### NetFlow v5 sampling rates are read from the packet header

Earlier releases ignored the v5 header interval.
What they recorded instead depended on the receiver:

- No fallback configured: every v5 flow was recorded as unsampled, `samplingInterval = 1`. Flows from an exporter that advertises a rate now carry that rate, so a query multiplying by `samplingInterval` returns a larger and more correct answer.
- `flow-sampling-interval-fallback` configured: every v5 flow carried the configured value, whatever the exporter said. The exporter's own rate now wins, so the recorded value can move in either direction. An exporter advertising 1:20 behind a receiver configured for 1000 drops from 1000 to 20, and a query multiplying by the rate returns an answer 50 times smaller than before, and correct where the old one was not.

Stored `bytes` and `packets` are unchanged either way, and no query errors.
Rows written before the upgrade are not rewritten: correcting them would need each exporter's rate at each past moment, which is exactly what was never recorded.
A query spanning the upgrade mixes both conventions.
`parsers.<name>.samplingRate.header` shows which receivers are now resolving from the header.

The rows whose meaning changed name themselves.
Find which exporters now report a rate, and when each changed:

```sql
SELECT exporterAddr, samplingInterval, min(timestamp), max(timestamp)
FROM riptide.flows
WHERE flowProtocol = 'NetflowV5' AND samplingProvenance = 'header'
GROUP BY exporterAddr, samplingInterval
ORDER BY exporterAddr, min(timestamp);
```

Expected output on a deployment with no NetFlow v5 exporter:

```text

```

On a deployment with v5 exporters, the `min(timestamp)` of the earliest row per exporter is when that exporter's rate started being read.
Rows from before the upgrade carry a different provenance (`fallback`, `assumed`, or `''` if they predate the column), so no timestamp guess is needed to tell the two conventions apart.

If you compensated for the old behaviour by hardcoding a multiplier in a dashboard or query, remove it, or you will now double-count.

To pin the old behaviour while you correct queries, set **`trust-header-sampling-interval=false`** on the affected `netflow5` and `multi` receivers; it is not accepted on other receiver types and fails startup there.
This only suppresses the header for exporters that leave the mode bits unset; an exporter stating mode 1 or 2 with a rate is always read, so for those the old value cannot be restored by configuration.
Set `flow-sampling-interval-fallback` to the value you were relying on if you need a specific rate recorded.
See [Sampling rates and provenance](../../architecture/sampling.md#netflow-v5-headers).

### A `samplingProvenance` column is added to `flows`

It records which rung of the resolution ladder supplied each row's `samplingInterval`, so a stored `1` stops being ambiguous between an exporter that said it does not sample and one that said nothing at all.
Manage mode adds the column in place with `ALTER TABLE … ADD COLUMN IF NOT EXISTS`: no operator action, no data loss, no rewrite.
A provisioned deployment fails fast naming the column; re-run `riptide onboard` to add it.
Existing rows read `''`, which means "written before this column existed" and is distinct from `assumed`.
They are not backfilled, because the information needed to reconstruct them was never recorded.
No existing column, value or query result changes.
See [Sampling rates and provenance](../../architecture/sampling.md).

### `applicationId` and `applicationSource` columns are added to `flows`

They carry the exporter's RFC 6759 application id and which rung named `application` (`exporter`, `rules` or `none`).
The mechanics are those of `samplingProvenance`: manage mode adds them in place, a provisioned deployment re-runs `riptide onboard`, and existing rows read `0` and `''`.
Where an exporter sends an application table, `application` now carries the exporter's name instead of the rule's, so the rollups by application change vocabulary for those exporters from the upgrade onward.
See [Enrichment](../../architecture/enrichment.md#application-names-from-the-exporter).

### `httpHost`, `httpUri` and `applicationDescription` columns are added to `flows`

The first two carry the HTTP host and URI a Cisco AVC exporter sends on the request record; the third carries the exporter table's description of `application` when the exporter named the flow.
The mechanics are those of `samplingProvenance`: manage mode adds them in place, a provisioned deployment re-runs `riptide onboard`, and existing rows read `''`.
`''` is also what a record without a host, a URI or a description produces, so on a row older than the upgrade the two cannot be told apart and nothing is backfilled.
No rollup changes.
See [Enrichment](../../architecture/enrichment.md#http-host-and-uri-from-cisco-avc).

### A whitespace-only `config.yaml` is a skip, not a failure

A whitespace-only `config.yaml` used to increment `config.reload.failures` and latch `config.reload.stale`.
It is now a skip, matching what the inventory file has always done.
If you alert on `config.reload.failures` to catch a `> config.yaml` truncation, that alert stops firing: the truncation surfaces as the once-per-episode warning and as reloads that stop happening.
See [How configuration reloads work](../../architecture/reloading.md).

### Rules that loaded before may be rejected

Rules with a condition column that resolves to nothing, or only in part, are rejected after an upgrade instead of being widened or narrowed silently.
Which of the two cases you have, and why one of them is a regression for that rule, is in [How configuration reloads work](../../architecture/reloading.md#classification-rules-that-loaded-before-may-be-rejected).

### SNMP cache keys are retired

The `riptide.snmp.cache.*` block no longer controls SNMP freshness. Interface tables are polled on a schedule, not cached per lookup.

| Retired | Replacement | Note |
| --- | --- | --- |
| **`riptide.snmp.cache.retention-ms`** | `riptide.snmp.polling.<name>.refresh-interval` | Not carried over automatically. The old value was a cache TTL (how long an answer stays usable); the new one is a poll interval (how often to ask). Adopting a 60 s retention would mean walking every exporter every minute, ten times the agent load, silently. Set it deliberately, in a polling profile. |
| **`riptide.snmp.cache.negative-retention-ms`** | none | Misses are no longer cached separately: an `ifIndex` absent from a polled snapshot is a known absence, so there is nothing to expire. |
| **`riptide.snmp.cache.dead-endpoint-retention-ms`** | `riptide.snmp.poll.dead-endpoint-base-ms` and `-ceiling-ms` | Unreachable endpoints back off exponentially instead of retrying at a fixed interval. |

Riptide logs a warning at startup for each of these it finds set; the value is ignored.
The exporter option table keeps its own retention under the key that names it, **`riptide.snmp.options.retention-ms`** (default `1200000`).

### Fleet poll cadence keys fail startup

The global `riptide.snmp.poll.refresh-interval-ms` and `riptide.snmp.poll.snapshot-expiry-ms` are retired and fail startup if set, in any file or environment spelling.
Cadence lives in named polling profiles under `riptide.snmp.polling.<name>` referenced from agent ranges; the remaining `riptide.snmp.poll.*` keys are fleet-level and keep binding, see the [enrichment reference](../../reference/enrichment.md#snmp-polling).
Expect exporter CPU to drop after the upgrade: agent load is now a function of the poll schedule rather than of how many distinct interfaces a device's flows reference.

### Re-run `onboard` after an upgrade that adds a rollup dimension or measure

A validate-mode collector (`manage-schema: false`) issues no DDL, so it cannot repair its own rollups.
Until `riptide onboard` is re-run, riptide reports all four rollups as not matching this version and declines them at query time, so every query spanning 60 minutes or more is answered from raw `flows`.
A query reaching past what raw `flows` retains comes back short, and the answer says so with a `coverage_warning` entry.
Then restart the collector: which rollups are usable is decided once, at startup, so a collector that declined them keeps answering from raw `flows` until it restarts, however complete the repair was.
Manage-mode deployments repair an added dimension themselves on the next start.
A missing summed measure is not repaired in either mode; a measure combined another way, such as the sampling-provenance bitmask `samplingProvenanceMask`, is added in place in manage mode and by `onboard`.
See [Rollups](../../architecture/rollups.md#rollups-gain-dimensions-in-place).

### Hand-created `samples` views need re-creating

The `samples` definition evolves: the bucket split was corrected in #270 (older definitions under-report traffic by up to the flow's bucket count), and the view body was restructured in #346 (older definitions are 5 to 15× slower on every query).
Manage-mode collectors heal on restart, because the view is `CREATE OR REPLACE`d.
A `samples` view an admin created by hand, in a provisioned deployment for example, keeps the old definition until it is re-created from the current one.

### Earlier riptide releases cannot start against ClickHouse 26.8

An earlier riptide fails with `flows table not found in database '…'` while the table is there.
The cause is in the ClickHouse Java client, whose schema endpoint response is parsed by a TSKV parser that 26.8 makes throw `Non-null columnName and columnType are required`.
Riptide now reads the column list from `system.columns`, which both server versions answer identically, so that parser is no longer in the startup path.
Upgrade riptide; there is no server-side workaround and no newer client to move to.

### `riptide.location` is deprecated

`zone` replaces the former `riptide.location` key.
`riptide.location` is still accepted and mapped to `riptide.identity.zone` with a warning at startup when the new key is unset.

### Do not drop the rollup materialized views when rolling back

Riptide does not support downgrading.
With the views in place an older riptide leaves them alone and keeps aggregating correctly into columns it does not read.
With them gone, it either stops feeding the rollup entirely or creates a narrow view over the still-wide target, writing the reserved value into a sorting-key column for the rollup's full 365-day retention.

### The rollups carry `samplingInterval` and `flowProtocol`

A validate-mode deployment (`manage-schema: false`) gains the two rollup dimensions only when an admin re-runs `riptide onboard`; until then a query naming them fails with `UNKNOWN_IDENTIFIER` and riptide answers long-range queries from raw `flows`.
Rows aggregated before each dimension was appended read `0` and `''`; the boundaries do not coincide because the two were appended in different releases.
See [Query sampling-corrected volume](../../guides/sampling-corrected-volume.md) for the predicates a rollup query needs.

### Object names carry their database since the rename

Users, roles and the quota are now `writer_<tenant>@<database>`, `bi_<tenant>@<database>`, `flow_writer@<database>`, `flow_reader@<database>` and `flow_ingest@<database>`.
A deployment onboarded before the rename keeps working on the unqualified names; re-running `onboard` adds the qualified account alongside the old one and warns about the leftover.
Migrate one tenant at a time and close each database with `revoke-legacy`, in the order [Migrate a deployment onboarded before the rename](../tenants/migrate-tenant-accounts.md) gives; dropping the old account too early takes every other database of that tenant offline.

### The dead-letter table and its per-user read grant arrive on re-onboard

`flows_dead_letter` is added with `onboard --create-schema`; a database provisioned before it existed keeps collecting, and a refused batch costs what it always cost until the table arrives.
Each tenant's `SELECT` on it is granted per user, together with its row policy, so a tenant onboarded before the table existed cannot read it until re-onboarded.
Restart the collector after adding the table.

### Discovery failure messages name the composed source

With discovery on, a failed inventory reads `Inventory source <file> + <endpoint> carries problems ...` and a top-level key problem is reported under "the document root" on both paths, where it used to say "the file root".
An alert or a log parser matching `Inventory file` keeps matching a file-only inventory and needs the second form when discovery is on.

## Related

- [ClickHouse reference](../../reference/clickhouse.md): the schema modes and what each one creates.
- [Rollups](../../architecture/rollups.md): why a validate-mode deployment must re-run `onboard` after a release adds a dimension.
- [Image tags](../../reference/management.md#image-tags): which tag moves on a release.

## Open questions

- The verify outputs come from the 0.15.0 jar on this machine against the local ClickHouse; `apt`, `dnf` and `docker compose pull` were not run for this page.
