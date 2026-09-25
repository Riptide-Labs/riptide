---
title: Troubleshooting
sidebar_position: 1
description: Symptom, cause and fix for what an operator sees in the probes, the metrics and the log, with a link to the page that owns each remedy.
---

# Troubleshooting

## Check

```bash
curl -s -i http://localhost:8080/readyz | head -1
curl -s http://localhost:8080/metrics | grep -E '^(config|inventory|classification)_reload_(stale|dead) |^classification_rules_rejected |^persister_batch_(droppedRows|failedRows|deadLetterFailedRows) '
```

Healthy output:

```text
HTTP/1.1 200 OK
classification_reload_stale 0.0
classification_rules_rejected 0.0
config_reload_dead 0.0
config_reload_stale 0.0
persister_batch_deadLetterFailedRows 0.0
persister_batch_droppedRows 0.0
persister_batch_failedRows 0.0
```

`config_reload_*` and `inventory_reload_*` appear only with `riptide.config.reload-interval` set; `classification_reload_dead` only with `riptide.classification.reload-interval` set.

## Diagnose

| Symptom | Cause | Fix |
| --- | --- | --- |
| `/readyz` answers `503` shortly after start | receivers start after the ClickHouse startup wait, up to `riptide.clickhouse.startup-wait` (30 s) | wait it out; if you raised the wait, raise the probe budgets with it, see [Management endpoints](../reference/management.md#health-endpoints--probes) |
| `/readyz` answers `503 receivers not listening: <name>` while running | a configured receiver failed to bind, or its socket died | read the receiver's startup error in the log; a port already in use or a bind address not on the host are the usual causes |
| `/livez` answers `503` | a started receiver's socket has died | restart the collector |
| `/metrics` answers `404` | `riptide.management.metrics-enabled=false` | set it to `true` |
| `/metrics` or a probe answers `503` under load | more than `riptide.management.max-concurrent-requests` (32) requests in flight | lower the scrape rate, or raise the cap |
| Collector starts, logs `No receivers configured`, reports ready | the shipped configuration declares no receivers | define `riptide.receivers.<name>`, see [Receivers reference](../reference/receivers.md) |
| `config_reload_failures` rises and `config_reload_stale == 1` | an edit to `config.yaml` failed validation; the running configuration is kept | read the WARN naming the problem, fix the file; the next poll commits it |
| `config_reload_stale == 1` and no failures rise | the last file that could be read did not commit; a later truncation does not clear it | look for the once-per-episode skip warning; a whitespace-only or missing file skips the cycle, see [How configuration reloads work](../architecture/reloading.md) |
| Edits to `config.yaml` stop applying, nothing counts as a failure | the file is missing, empty or whitespace-only, so every cycle skips; or `env://` references cannot rotate in-process | restore the file; restart for an environment change |
| `config_reload_dead == 1` or `inventory_reload_dead == 1` | the poll schedule stopped and will not run again, typically an `Error` such as OOM on an oversized file; a deliberate shutdown reads 1 too | restart; scope the alert to processes expected to be up |
| `config_reload_stale` absent | reloading is not enabled | set `riptide.config.reload-interval`, see [Enable configuration hot reload](../guides/hot-reload.md) |
| SOPS secret rotated, collector still uses the old value | the decrypted-file cache drops only on a `config.yaml` content change | touch or edit `config.yaml` |
| `classification_rules_rejected > 0` | part of the ruleset classifies nothing: a condition column that resolves to nothing or only in part, or a value in `exporterFilter` | read the WARN naming the rule and the ERROR naming the column and value; fix the row, see [Write a classification rule](../guides/classification-rules.md) |
| `classification_rules_rejected == -1` and `classification_reload_stale == 0` | the boot load has not published yet; classification blocks until the first tree build finishes | wait; at the size bound that is four to five seconds, see [Classification tree build cost](../architecture/classification-build-cost.md) |
| `classification_rules_rejected == -1` and `classification_reload_stale == 1`, flows log an ERROR on classification | the initial load failed; no rules have ever published | make the resource readable; with a reload interval the next poll picks it up, otherwise restart |
| `classification_reload_stale == 1` with rules still serving | the last fetch or load failed and no later one succeeded; the previous rules keep classifying | read the WARN naming the cause; a failed ruleset is attempted once, so fix it and the next poll applies the fix |
| `classification_reload_stale == 0` for hours while the source is a 404 or an empty body | a skipped cycle does not recompute the gauge | alert on the once-per-episode skip warning or on the absence of reload successes |
| Collector will not start, log names the rules resource | the eager startup parse of `riptide.classification.rules` failed, or an `http(s)://` rules server is down | fix the resource; a local file has no startup coupling to a server |
| A `classpath:` ruleset never reloads | a resource inside the packaged jar cannot change | point `riptide.classification.rules` at a file or a URL |
| WARN naming a ruleset that exceeds the supported size | `classification.rules.preprocessed` above 25,000 | see [Classification tree build cost](../architecture/classification-build-cost.md); nothing fails |
| A ruleset edited often never publishes | a poll that finds changed bytes cancels a build in progress and starts again | keep `riptide.classification.reload-interval` comfortably above the build time |
| `listeners_<name>_socketDrops` rising | the kernel receive buffer overflowed before riptide ran | raise `net.core.rmem_max`, or reduce offered load |
| `listeners_<name>_socketDrops` absent | non-Linux platform; the value comes from `/proc/net/udp` | nothing to fix; absent is not zero |
| `parsers_<name>_undecodableSets` rising after startup | Data Sets arriving before their Template; a burst at startup is normal, a sustained rate is not | shorten the exporter's template refresh; the count includes Options Data Sets, so it is not proof of flow loss |
| `parsers_<name>_dispatchDrops` rising | enrichment or persistence fell behind the UDP receiver | see [Where flows can be lost](../architecture/loss-accounting.md); an IPFIX over TCP receiver blocks instead of dropping |
| `parsers_<name>_unmodelledElementTemplates > 0` | an exporter announces an IPFIX element riptide parses and discards, today IE 390 to 399 (flow selection) | read the log line naming the exporter and observation domain; decide whether that exporter's rate is trustworthy, see [issue 596](https://github.com/Riptide-Labs/riptide/issues/596) |
| `parsers_<name>_sessionCount` dropped after an upgrade to 0.7.0 | the gauge now counts exporters, not templates | use `templateCount` for the old quantity, see [Upgrade riptide](../guides/upgrade.md#070-sessioncount-means-exporters-not-templates) |
| `parsers_<name>_sessionCount == 0` on a NetFlow v5 or sFlow receiver | those protocols carry no templates | nothing to fix |
| A `parsers_<name>_*` series vanished | the parser stopped; its gauges deregister with it | alert on absence, not on a zero |
| `persister_batch_droppedRows` rising | the persister queue is full: ClickHouse cannot keep up, or the collector is shutting down | watch `persister_batch_queueDepth` against `riptide.clickhouse.batch.queue-capacity`; see [Insert batching and dead letters](../architecture/persistence.md) |
| `persister_batch_failedRows` rising with `Failed to persist a batch of N flows` in the log | ClickHouse rejected a batch riptide had accepted | read the error in the log; the rows are in `flows_dead_letter` when `deadLetteredRows` moved with it, see [Inspect and replay dead letters](dead-letters.md) |
| `persister_batch_deadLetterFailedRows` rising | refused rows could not be kept: the deployment predates `flows_dead_letter`, or the server is unreachable | re-run `riptide onboard --create-schema` and restart, see [Inspect and replay dead letters](dead-letters.md#add-the-dead-letter-table) |
| `pipeline_dispatchErrors` rising with batching off | enrichment or persistence threw, including a refused insert on the per-record path | read the log; with batching on the same refusal is a `failedRows` case instead |
| `parsers_<name>_samplingRate_header` fell to zero on a fleet that used to advertise | v5 exporters stopped stating a rate; flows are recorded at the fallback or an assumed `1` | check the exporters' sampling configuration, see [Sampling rates and provenance](../architecture/sampling.md) |
| `management-http-*` threads missing from `jstack` | virtual threads are invisible to `jstack` and `top -H` | `jcmd <pid> Thread.dump_to_file -format=json /tmp/threads.json` |
| Profiling on, `Profiling started` logged, nothing reaches the server | `PYROSCOPE_SERVER_ADDRESS` unset, so the agent uploads to `localhost:4040` | set it, see [Enable continuous profiling](../guides/profiling.md) |
| Profiling on, `Restricted methods will be blocked in a future release` warned every start | `--enable-native-access=ALL-UNNAMED` not reaching the JVM; `JAVA_OPTS` works only on the deb and rpm | set `JDK_JAVA_OPTIONS`, confirm with `/proc/<pid>/cmdline` |
| `PYROSCOPE_PROFILER_TYPE=JFR` and profiling refuses to start | JFR rejects the default `itimer` event and `wall` | add `PYROSCOPE_PROFILER_EVENT=cpu` (or `alloc`, `lock`) |
| Collector killed mid-shutdown, buffered flows lost | the service manager's stop timeout is shorter than the shutdown sequence | budget `TimeoutStopSec` for `(5 s × parsers) + shutdown-grace-period + 1 s + 2 s + 2 s`, see [Insert batching and dead letters](../architecture/persistence.md) |
| Startup fails naming `riptide.nodes` or a retired `riptide.snmp.poll.*` key | a removed configuration tree survived the upgrade | convert it, see [Upgrading from 0.8](../guides/upgrading-from-0.8.md) |
| Flows from a new exporter carry no `inputSnmpIfName`, `ifAlias` or `ifSpeed` | The exporter's first interface walk has not completed; there is no snapshot until it does | Wait one refresh interval. Static interface pins and exporter-pushed option records still apply, see [Enrichment](../architecture/enrichment.md#interface-tables-are-polled-not-looked-up) |
| A newly added interface resolves only minutes later | Snapshots are refreshed on the poll schedule, and an unresolvable `ifIndex` never triggers an early walk | Lower `refresh-interval` in the range's polling profile, see [Enrichment reference](../reference/enrichment.md#snmp-polling) |
| Startup warns `snmp poll snapshot expiry ... is shorter than the refresh interval` and interface fields blank between walks | `snapshot-expiry` is shorter than `refresh-interval` in a polling profile | Set expiry at or above refresh |
| Startup fails with `Retired per-agent poll key found` | `riptide.snmp.poll.refresh-interval-ms` or `.snapshot-expiry-ms` is set, in any spelling | Move the cadence into `riptide.snmp.polling.<name>`, see [Enrichment reference](../reference/enrichment.md#retired-keys) |
| Startup warns `riptide.snmp.cache.retention-ms=... is IGNORED` | A pre-polling key is still set | Delete it; set the poll interval deliberately in a polling profile, see [Upgrade riptide](../guides/upgrade.md) |
| `enrichment_application_unresolved` climbs on one exporter | The exporter sends `applicationId` but its application table has not arrived, or it sends none | Wait one table refresh interval; on a device that exports ids without a table the rules rung names it and the meter keeps moving |
| A Juniper SRX names every flow by the rules although AppID is on | The SRX writes `applicationId = 0` and sends no application table | Nothing to configure; tracked in [#848](https://github.com/Riptide-Labs/riptide/issues/848) |
| `httpHost` is empty on rows that carry bytes | HTTP host and URI ride the request record only; response and reclassified records read `''` | Count request records rather than bytes in a panel that ranks hosts |
| Flows land minutes outside every dashboard window | The exporter's clock is skewed and skew correction is off (`skew-threshold-ms=0`) | Set `riptide.enricher.clock-correction.skew-threshold-ms` above the fleet's normal export delay and fix the device's NTP, see [Enrichment](../architecture/enrichment.md#clock-correction) |
| Startup fails naming `trust-header-sampling-interval` or `flow-sampling-interval-fallback` on a receiver | The key was set on a receiver type that does not define it (`trust-header-sampling-interval` exists on `netflow5` and `multi` only; `flow-sampling-interval-fallback` not on `sflow`). | Move the key to a receiver of the right type; see [Receivers reference](../reference/receivers.md#keys-by-receiver-type). |
| Startup fails naming `riptide.flows.session.*` | A session bound is zero or negative. | Set a positive value; see [Receivers reference](../reference/receivers.md#session-state-bounds). |
| Startup WARN that the template timeout outlives `riptide.flows.session.source-idle-timeout` | The slot expires before the state it authorises. | Raise `source-idle-timeout` to at least the template timeout. |
| Flows from a sampling NetFlow v9 exporter are recorded with `samplingInterval = 1` and provenance `assumed` | The exporter is not sending its sampler options table, or the collector restarted and the table has not been re-sent yet. | Enable `option sampler-table timeout <seconds>` (IOS-XE) or `options sampler-table timeout` (IOS-XR) on the exporter; set `flow-sampling-interval-fallback` on the receiver as a last resort. See [Sampling rates and provenance](../architecture/sampling.md). |
| One exporter alternates between two `samplingProvenance` values | Firmware populating the sampling field on some export paths only, or a sampler table expiring between refreshes. | Run the per-exporter query in [Query sampling-corrected volume](../guides/sampling-corrected-volume.md#find-exporters-whose-rate-is-not-resolving-consistently); shorten the exporter's option refresh interval. |
| `parser.optionSampling.expired` climbing | An exporter stopped advertising its rate more than 24 h ago and its flows fell to the fallback or `assumed`. | Check the exporter's sampler-table refresh; see [Sampling rates and provenance](../architecture/sampling.md#how-long-a-learned-rate-is-held). |
| `parser.options.recognisedUnusable` climbing | An exporter states something riptide understands but cannot keep, most costly a sampling algorithm whose ratio cannot be stored. | Read which `_skipped` meter moves with it; see [Option records nobody used](../architecture/sampling.md#option-records-nobody-used). |
| A query against raw `flows` fails with `Code: 691 … UNKNOWN_ELEMENT_OF_ENUM` | `flowProtocol != ''` was copied from a rollup query; in `flows` the column is an `Enum8`. | Drop the boundary predicates on raw `flows`; see [Query sampling-corrected volume](../guides/sampling-corrected-volume.md). |
| `flows.session.rejectedSources` or `rejectedScopes` rising steadily on a healthy fleet | The bound is lower than the hardware needs. | Raise the matching `riptide.flows.session.*` key; see [Exporter identity and session state](../architecture/session-state.md#what-happens-when-a-bound-is-reached). |
| Startup fails with `ClickHouse at <endpoint> did not answer within <window>` | Nothing answered within `riptide.clickhouse.startup-wait` | Start the server, check `riptide.clickhouse.endpoint`, or raise the wait, see [Startup wait](../reference/clickhouse.md#startup-wait) |
| Startup fails with `flows table not found in database '…'` on ClickHouse 26.8 with the table present | An earlier riptide's client could not parse 26.8's schema endpoint | Upgrade riptide; it reads `system.columns` now |
| Startup fails with `flows table not found` in validate mode | The database was never provisioned | `riptide onboard --create-schema`, see [Onboard a tenant](../guides/onboard-a-tenant.md) |
| Startup fails naming a missing column | The on-disk `flows` table predates a column riptide inserts | Manage mode adds it on the next start; validate mode: re-run `riptide onboard` |
| Startup fails with `riptide.clickhouse.batch.shutdown-grace-period (…) must be at least twice max-latency` | Grace period too short | Raise the grace period or lower `max-latency` |
| Inserts fail with `No serializer found for column '…'` | A plain `DEFAULT` column riptide has no value for | Make it `MATERIALIZED` or `ALIAS`, or drop it |
| `WARN Rollup X …` at startup and long-range queries slower or shorter than expected | Rollup shape drift, a missing grant, or a database onboarded without `--create-schema` | [Recover from a rollup shape message](rollup-drift.md) |
| A query on a rollup fails with `UNKNOWN_IDENTIFIER` for `samplingInterval` or `flowProtocol` | Validate-mode deployment whose rollups predate those dimensions | Re-run `riptide onboard`, then restart the collector |
| Rollups expire before the raw rows | `--ttl-days` above 365 with rollups left at 365 | `ALTER TABLE <db>.<rollup> MODIFY TTL timestamp + INTERVAL <n> DAY` for each rollup |
| The `samples` view answers wrong or slow after an upgrade in a provisioned deployment | A hand-created `samples` view keeps its old definition | Re-create it from the current definition; manage-mode collectors heal on restart |
| A rollup total spanning an upgrade comes back too small | Rows aggregated before a dimension was carried read `0` or `''` | Add the boundary predicates, see [Query sampling-corrected volume](../guides/sampling-corrected-volume.md) |
| `onboard` fails with `database 'riptide' has no flows table — re-run with --create-schema …` | first run on a fresh server, or a mistyped `--database` | add `--create-schema` on a single node, or pre-create `flows` admin-side on a cluster; see [Onboard a tenant](../guides/onboard-a-tenant.md) |
| `onboard` fails with `… is missing the 1-minute rollup tables or their materialized views — re-run with --create-schema …` | database provisioned before the rollups existed, or a bootstrap interrupted between targets and views | re-run with `--create-schema`, then restart the collector; see [Add the rollups to an existing deployment](../guides/onboard-a-tenant.md#adding-rollups-to-an-existing-deployment) |
| `onboard` fails with `… is missing the dead-letter table (flows_dead_letter) — re-run with --create-schema …` | database provisioned before `flows_dead_letter` existed | re-run with `--create-schema`, then restart the collector; see [Add the dead-letter table](../guides/onboard-a-tenant.md#adding-the-dead-letter-table-to-an-existing-deployment) |
| `onboard` aborts with `could not check whether tenant … still has pre-rename (database-unqualified) accounts … GRANT SHOW USERS ON *.* TO <your --admin-user>` | the admin lacks `SHOW USERS`; `CREATE USER` and `DROP USER` do not imply it | grant it and re-run; nothing was changed |
| `onboard` prints `warning: the pre-rename account 'writer_<tenant>' still exists on this server …` | the tenant was onboarded before names carried the database | follow [Migrate a deployment onboarded before the rename](../guides/migrate-tenant-accounts.md) |
| the collector logs `Code: 469 … tenant_pinned … (VIOLATED_CONSTRAINT)` and `persister.batch.failedRows` climbs | `riptide.identity.tenant` or `riptide.identity.organisation` does not match the writer credential's `CONST` settings | paste the stanza `onboard` printed for that tenant; see [What the barrier guarantees](../architecture/multi-tenancy.md#what-the-barrier-guarantees) |
| a writer's insert is refused with `ACCESS_DENIED` on another database | the role is per database; the grant is not there | onboard the tenant into that database, which creates a separate `writer_<tenant>@<database>` |
| a BI user reads every tenant's rows | the user holds `SELECT` and is named by no row policy on that table; `users_without_row_policies_can_read_rows` defaults to `true` | re-run `onboard` for that tenant so the policy names it; never widen or trim a policy by hand; see [Row policies are not deny-by-default](../architecture/multi-tenancy.md#dropping-the-roles) |
| a BI user cannot read `flows_dead_letter` at all | the tenant was onboarded before the table existed; its `SELECT` is per user and arrives with the policy | re-run `onboard` for that tenant |
| a JDBC or Grafana-by-URL connection as `writer_acme@riptide` fails with a host or auth error | the `@` ends the URL's userinfo | write `writer_acme%40riptide` in URLs; the plain username fields need no encoding |
| `revoke-legacy` refuses with `its row policies still name the database-unqualified grantees …` | a pre-rename account or a hand-added grantee still serves this database | finish steps 1 to 4 of the migration for its tenant; see [When it refuses](../guides/migrate-tenant-accounts.md#when-it-refuses) |
| `revoke-legacy` refuses with `it has NO row policies …`, `applies to ALL principals …` or `holds a grant WIDER …` | nothing to reason from, an unenumerable policy, or a grant beyond these tables | see [When it refuses](../guides/migrate-tenant-accounts.md#when-it-refuses) |
| `revoke-legacy` refuses with `could not read which grants …` or `could not check whether a pre-rename grantee …` | `system.grants` or `system.row_policies` refused to the admin | grant `SELECT` on the named system table and re-run |
| `revoke-legacy` reports `the revoke failed part-way through` | a `REVOKE` failed mid-run, usually a missing `GRANT OPTION` | grant it and re-run; the statements are idempotent |
| `offboard --database db_b` took an unmigrated `db_a` offline for the same tenant | the pre-rename accounts are instance-wide and keyed on the tenant alone | re-onboard the tenant in `db_a` to give it a `@db_a` account; see [Offboard reaches every database](../guides/migrate-tenant-accounts.md#offboard-reaches-every-database) |
| a query against `bi_<tenant>@<database>` in Grafana returns another tenant's rows | per-tenant datasources in one shared org, or a `$tenant` variable used as a boundary | one Grafana org or instance per tenant; see [Grafana topology](../architecture/multi-tenancy.md#grafana-topology) |
| Startup fails: `<file> declares an 'exporters' tree while riptide.discovery.url is set` (or `riptide.discovery.urls`) | Both the inventory file and discovery define exporters | Remove the `exporters` tree from the file, or unset the key the message names. See [What discovery owns](../architecture/discovery.md#what-discovery-owns). |
| Startup fails, or every poll fails, with `yielded no exporter entries` right after a filter was added | The filter matches nothing, or the token lost permission | Develop the filter against the endpoint directly; check the token. Riptide refuses an empty answer rather than wiping every exporter name. |
| Poll fails with `returned N exporter name(s) claimed by more than one entry` | Two devices with one name and different addresses; NetBox enforces name uniqueness per site only | Rename or filter one out. Every collision is listed under the message. |
| Poll fails with `returned N exporter address(es) claimed by more than one entry` | An HA pair or virtual-chassis pair sharing one primary IP | Give one a different primary IP or filter one out. |
| Every poll is `HTTP 400 {"ordering":["Unknown filter field"]}` | `netbox-api` pointed at Nautobot | Use `mapped-json`, see [Discover exporters from Nautobot](../guides/discovery-nautobot.md). |
| Nautobot: every device skipped, `discovery.skipped` equals the fleet | `filter: depth=1` missing, so `primary_ip4` is a reference with no `host` | Add `depth=1` to `riptide.discovery.filter`. |
| Poll fails with `mapped N address(es) that carry a prefix length` | The mapped field serves `10.0.0.1/24` | Map the bare-host field (`primary_ip4.host` on Nautobot), serve the address without the prefix, or use `netbox-api` if it is NetBox. |
| `WARN Boot could not reach <endpoint>` and flows carry no exporter names | Endpoint down, refused, timed out or 404 at boot | Wait for the retry at `riptide.discovery.interval`; with a non-positive interval, restart once the endpoint is back. See [Startup with the endpoint down](../architecture/discovery.md#startup). |
| `inventory.reload.stale` stays 1 after the endpoint came back | `riptide.discovery.interval` is zero or negative, so no watcher runs | Set a positive interval and restart, or restart now. |
| Startup fails with `did not answer with a JSON array` | `prometheus-sd` pointed at `/api/dcim/devices/` | Set `type: netbox-api`, or point at the plugin endpoint. |
| Startup fails with `did not answer with a NetBox device page` | `netbox-api` pointed at the plugin endpoint | Point at `/api/dcim/devices/`, or set `type: prometheus-sd`. |
| Startup fails with `address-labels cannot be customised while riptide.discovery.type is 'netbox-api'` | Labels customised for another producer, then the type switched | Remove `riptide.discovery.address-labels`. |
| Startup fails with `auth-scheme must not be blank when riptide.discovery.token is set` | `RIPTIDE_DISCOVERY_AUTH_SCHEME` exported empty | Unset it or set `Token`. |
| Poll fails with `gave a 'next' page on a different origin` | A reverse proxy rewriting the `next` link's host or scheme | Fix the forwarded host in front of the endpoint. |
| Poll fails with `served more than 10000 pages` or `returned more than 100000 devices` | A `next` link that never ends, or an unbounded inventory | Fix the endpoint's paging, or narrow with `riptide.discovery.filter`. |
| Endpoint uses an internal CA and every poll fails on TLS | No trust for the CA | Set `riptide.http.ca-bundle`; verification cannot be disabled. See [Outbound TLS](../reference/outbound-tls.md). |

## Escalate

Open an issue at [Riptide-Labs/riptide](https://github.com/Riptide-Labs/riptide/issues) with the collector version, the startup log with stack traces, and the output of the check above.
