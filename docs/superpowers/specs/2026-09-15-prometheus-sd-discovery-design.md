# Dynamic exporter discovery from Prometheus HTTP service discovery

Status: approved design, not yet implemented.
Date: 2026-09-15.

## Problem

Exporter enrichment requires an operator to write every device into the inventory file by hand.
Sites that already run NetBox as their source of truth have that list once and maintain it once.
Copying it into a second file makes it wrong the moment a device is added, renamed or retired.

The request that started this was framed as "do what gnmic does for NetBox".
That framing does not survive contact with gnmic.

## Correcting the premise

gnmic has no NetBox loader.
Verified three ways against the tree at commit `b262bf03`:

- `pkg/loaders/loaders.go` hardcodes `LoadersTypes = []string{"file", "consul", "docker", "http"}`.
- `pkg/loaders/all/all.go` blank-imports only those four.
- A grep for `netbox` across a fresh clone returns zero files.

Loaders are not pluggable, so no third-party NetBox loader can be installed either.
The key `target-template` does not exist anywhere in gnmic, and its template engine is Go `text/template` with gnmic's own thirteen functions, not sprig.
The NetBox loader that does exist belongs to `openconfig/gnmi-gateway`, a different project.

The consequence for this work: copying the shape of that configuration would let nobody reuse anything, because the file it came from is not a file anyone has.
Reuse has to anchor to something operators genuinely run.

That thing is Prometheus HTTP service discovery.
`FlxPeters/netbox-plugin-prometheus-sd` is deployed widely, is listed on the Prometheus integrations page, and emits a format that several other producers emit too.
Real gnmic users reach NetBox through the generic HTTP loader pointed at that same plugin.

## Scope

In scope: consuming a Prometheus HTTP service discovery document to populate the `exporters` tree, so flows get a device name instead of a bare address.

Out of scope, each with a reason:

- SNMP agent ranges from discovery. Ranges are a handful of CIDRs that gain nothing from per-device discovery.
- Interface pins from discovery. Riptide already resolves interfaces from SNMP and from IPFIX option data, and NetBox interface records are frequently stale.
- Site, role and vendor labels on flows. `ExporterEntry` has no label field and the ClickHouse flow schema has no home for one. That is a schema change and a separate piece of work.
- A NetBox-native client, a generic HTTP loader with operator-written field mapping, and Nautobot support. Tracked as follow-up epics.
- Custom certificate authority configuration. Inherits the existing limitation of the classification rules source: an internal CA needs the JVM trust store.

## What the format actually gives us

The Prometheus contract is a bare JSON array, with `Content-Type: application/json`, status 200, UTF-8.
Each element has exactly `targets`, an array of strings, and `labels`, a string-to-string object.
There is no `results` envelope.

Three facts from the NetBox plugin's serializer drive the mapping, and two of them are counterintuitive.

**A device's target is its name, not an address.**
`PrometheusTargetsMixin.get_targets` returns `[obj.name]`, with `:<port>` appended only when the device's config context supplies an integer port.
The upstream test `test_device_full_to_target` sets `primary_ip4`, `primary_ip6` and `oob_ip` on a device and still asserts the target is `firewall-full-01`.
Addresses are available only as labels.

**Every IP has its mask already stripped.**
Both the target path and every label path run through `IPNetwork(...).ip`.
A device stored as `192.168.0.1/24` emits `192.168.0.1`.
No prefix length is recoverable from this API, and no mask-stripping logic is needed on our side.

**The relevant label names are `__meta_netbox_name`, `__meta_netbox_primary_ip4` and `__meta_netbox_primary_ip6`.**
It is `__meta_netbox_role`, not `__meta_netbox_device_role`, and there is no serial label.
A label is omitted entirely when its field is empty, rather than emitted blank.

Two operational properties matter for cost.
Pagination is disabled on every endpoint, deliberately, because Prometheus does not support paged results.
NetBox's entity tag mixin is not applied to these viewsets, so conditional requests cannot avoid a re-transfer.
Every poll is therefore a full serialization of every visible device.

## Mapping

One fixed rule, one option.

**Name.** `__meta_netbox_name` when present. Otherwise the target string with a trailing `:<port>` removed, handling the bracketed IPv6 form.

**Address.** The first label present from an ordered list, defaulting to `__meta_netbox_primary_ip4` then `__meta_netbox_primary_ip6`.
When no label in the list is present, fall back to the target itself, parsed as an address with an optional port.

That fallback is what makes non-NetBox producers work, because generic service discovery puts a real address where NetBox puts a name.
It also covers the NetBox export-template variant, since operators writing those templates copy the plugin's label names.

Four cases need explicit treatment, because each is otherwise a silent corruption.

| Case | Behaviour |
|---|---|
| No usable address | Entry skipped, counted, reported. Never guessed at. |
| A group with several targets | One entry per target. They share a label set and would otherwise collide on name. |
| Two entries resolving to the same name | Refusal naming every collision. NetBox enforces name uniqueness per site, not globally. |
| A response parsing to zero entries | Refusal. The previous inventory keeps serving. |

A refusal is a throw from the source, not a quietly empty render.
`FileWatchTrigger.poll()` counts it in `inventory.reload.failures`, latches the staleness gauge and routes it to the owner's failure sentence, which is exactly the treatment a malformed inventory file already gets.
A quietly empty render would instead reach the regression guard, which refuses without counting anything, and the operator would see a stalled inventory with no failure recorded anywhere.

The zero-entry refusal is a deliberate divergence from gnmic, where a successful empty response deletes every target.
A filter typo or a permission change in NetBox must not be able to wipe every exporter name.
Riptide's existing guard already refuses a publish that empties a tree unless the emptiness is written out explicitly, so the renderer simply never writes an explicit empty mapping.

## Architecture

The feature adds a fetcher and a renderer.
Everything else is inherited.

`FileWatchTrigger` already provides an mtime-independent content-hash poll behind a `Source` seam whose three answers are `Present`, `Absent` and `Vanished`.
`ClassificationRulesSource` already drives that seam over HTTP.
`InventoryLoader` already parses and validates strictly and, since #630, accumulates every bad entry into one report.
`Inventory` already guards publication against dropping a whole tree and against a profile rotation landing mid-parse.
`InventoryFileReloader` already owns the commit path, the success and failure counters, the staleness and dead-schedule gauges, and the post-swap call to `InterfaceSnapshotPoller.refreshRegistrations()`.

None of that is modified.

### Composition, and why discovery gets no publisher of its own

`Inventory` holds one immutable `InventorySnapshot` carrying both trees, published by a single volatile write.
Discovery owns the `exporters` tree; the file keeps owning `snmp.agents`.

A discovery publisher of its own would publish an empty agents tree.
`InventorySnapshot.isRegressiveOver` would then either refuse that publish forever or, once the emptiness were declared, deregister the entire polled fleet.
So discovery does not publish.

Instead the document handed to `InventoryLoader` becomes a composition: agent ranges read from the file, exporters rendered from the endpoint, merged into one document.
One document, one hash, one commit path, no new publisher.
A file edit and a NetBox change are seen by the same cycle, and neither swaps anything unless the merged result differs.

The merge is structural, not textual.
The composing source loads the inventory file with SnakeYAML, replaces the `riptide.exporters` subtree with the rendered one, and dumps the result for `InventoryLoader` to parse and validate as a whole.
Textual splicing is rejected because it would have to locate a subtree by position in a file an operator hand-writes.

Round-tripping the file's `riptide.snmp` subtree through a load and a dump loses comments and formatting, which the loader never reads, and collapses a duplicate key to its last occurrence.
That collapse is not a behaviour change: the loader already parses the same file with the same SnakeYAML semantics, so a duplicate key resolves identically today.

### Every site that reads the inventory file

The composition must reach all four, and two of them are not obvious.

| Site | Why it matters |
|---|---|
| `Inventory.load()` | Boot. |
| `InventoryFileReloader.reload(byte[])` | The inventory watcher. |
| `Inventory.rebuildAndSwap(profiles, file)` from `ConfigFileReloader:259` | A credential rotation. |
| `Inventory.rebuildAndSwap(profiles, file)` from `ConfigFileReloader:434` | The pending-rebuild retry. |

Missing the last two would be a live bug rather than a gap.
A credential rotation would rebuild an inventory carrying no exporters, the guard would refuse it, and rotation would wedge until the process restarted.
The two `rebuildAndSwap` sites funnel through one method, so the seam is three edits: replace the `Path` parameter on `Inventory.load()` and `Inventory.rebuildAndSwap()` with the composed document, and give `InventoryFileReloader` the composing source.

### Determinism

The renderer emits entries in sorted order and produces byte-identical output for equivalent input.
This is load-bearing, not cosmetic.
The Prometheus contract states that target lists are unordered, and the hash short-circuit in `FileWatchTrigger.poll()` is the only thing preventing an unstable response ordering from swapping the inventory on every poll.

### Shared bounded HTTP read

The bounded read in `ClassificationRulesSource` is extracted rather than copied.
It already carries a connect and read timeout, an overall deadline, a response size ceiling, byte-order-mark stripping, a 404 mapped to `Absent` rather than a throw, error-stream draining before connection release, and a `describe()` that redacts credentials from the logged URL.

Extraction is chosen over a second copy because divergent copies of this loop are what #542, #560 and #561 were about, and because the shared trigger's own javadoc records two copies silently drifting on exactly this kind of detail.

The extraction adds one capability the classification source does not need: a request header, for `Authorization`.

## Configuration

Six keys under `riptide.discovery`, each named with the consumer that reads it.

| Key | Default | Consumer |
|---|---|---|
| `url` | none; absence disables the feature | the fetcher |
| `token` | none | the fetcher, as a `SecretRef` like every other credential here |
| `auth-scheme` | `Token` | the fetcher, building the `Authorization` header |
| `interval` | `60s` | the trigger's schedule |
| `timeout` | `10s` | the bounded read |
| `address-labels` | `__meta_netbox_primary_ip4,__meta_netbox_primary_ip6` | the renderer's address rule |

The default interval is a minute rather than the file watcher's interval because every poll is a full dump.
The response size ceiling is a constant, not a key, following `MAX_BYTES` in the classification source.

There is no `type` key.
Adding one later with a default that preserves behaviour is not a breaking change, so it buys nothing today.

Writing `exporters` into the inventory file while discovery is enabled is a startup failure naming both locations.
That is how this repo already handles configuration that has moved, and it is what keeps the either-or rule true per tree.

Boot does not fail when the endpoint is unreachable.
It warns, serves the file-only inventory, and lets the schedule heal.
A flow collector that refuses to start because NetBox is down is worse than one that starts without device names.
The existing staleness gauge is what makes that state visible.

## Metrics

The existing `inventory.reload.successes`, `inventory.reload.failures`, `inventory.reload.stale` and `inventory.reload.dead` cover the composed source with no change, which is a direct consequence of discovery having no publisher of its own.

Two gauges are added, because the skip and refusal paths above are otherwise invisible:

- `discovery.targets` — entries in the last rendered document.
- `discovery.skipped` — entries dropped for want of a usable address in that same render.

## Testing

Unit coverage on the renderer, one test per property:

- Name from `__meta_netbox_name`; name from a target with a port; name from a bracketed IPv6 target with a port.
- Address from the first present label in the configured order; address from the target when no label matches.
- An entry with no usable address is skipped and counted.
- A group with several targets renders one entry per target.
- Colliding names are refused, and the message names every collision, not the first.
- A document rendering zero entries is refused.
- Byte-identical output for the same groups supplied in a different order.

Unit coverage on the parser: a top-level object rather than an array, a missing `targets` field, a non-string label value, and a document exceeding the size ceiling are each rejected with a message naming the problem.

Integration coverage against a stub HTTP server, named `*Test` rather than `*IT`.

`*IT` classes run only under `mvn verify -Pe2e` and require Docker, because that profile is where failsafe is configured.
These tests need a loopback HTTP server and no container, and the repo already has that exact shape in `HttpRulesRefreshOnIntervalTest`, which boots a Spring context against a `com.sun.net.httpserver.HttpServer` and still runs in the default gate.
Naming them `*IT` would quietly exclude them from `make jar`.

- A canned document publishes, and flows carry the discovered name.
- A changed document swaps; an unchanged document does not.
- A 404 keeps the last good inventory serving and latches the staleness gauge.
- An empty array is refused and the previous inventory keeps serving.
- A credential rotation through `ConfigFileReloader` preserves the discovered exporters. This is the regression test for the two `rebuildAndSwap` sites, and it is the one most likely to be omitted.

`make jar` is `mvn verify` and runs no `*IT` class; `make e2e` is `mvn verify -Pe2e` and needs Docker.
Any claim that this feature works end to end has to name the run that produced it.

Every assertion above must be shown to fail before it is trusted.
Mutations run per property, against only the test naming it, with the baseline confirmed green first, and with the build confirmed to have reached the test phase.

## Documentation

A new page at `docs/docs/configuration/discovery.md`.

The either-or rule is a sibling that lives in prose as well as in code, so `configuration/exporter-enrichment.md` and `configuration/agent-configuration.md` each need a sentence saying which tree discovery owns and which it does not.
A reader reaches the enrichment page first and would otherwise write an `exporters` tree that fails startup without knowing why.

## Follow-up work

Three epics, deliberately excluded here:

1. #799 — a NetBox-native client reading `/api/dcim/devices/` directly, so sites without the service discovery plugin need nothing extra installed. Would introduce the `type` key, and would recover both the CIDR mask and real pagination, neither of which survives the service discovery plugin.
2. #800 — a generic HTTP loader with operator-written field mapping, for sources that emit neither the Prometheus format nor NetBox's.
3. #801 — Nautobot support. Its REST API is a near-identical NetBox fork, so this is close to free once the native client exists. The Nautobot service discovery plugin fork appears abandoned, so this depends on #799 rather than on the format.

   *Correction, 2026-09-18, measured against Nautobot 3.2.5:* "close to free once the native client exists" did not hold. The native client cannot read Nautobot at all, because it appends `ordering=id` and Nautobot answers 400; the fork diverges on query terms, not only on fields. What does read it is the generic mapped source from #800, with `depth=1` and `primary_ip4.host`. See the Nautobot section of the discovery page.

Also filed rather than fixed: #802, custom certificate authority configuration for outbound HTTP, which affects the classification rules source equally.
