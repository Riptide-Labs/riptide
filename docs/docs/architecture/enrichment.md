---
title: Enrichment
sidebar_position: 1
description: How every flow is enriched from a ladder of sources that degrades to the packet's own data, why SNMP is polled rather than looked up, how application names and Cisco AVC fields are read, and what clock correction does.
---

# Enrichment

Every flow passes an asynchronous enrichment pipeline before persistence.
Enrichment never blocks or drops flows: a failure degrades to an unenriched flow with a logged warning.
The keys that tune it are on the [enrichment reference](../reference/enrichment.md), and the rule format is in [Write a classification rule](../guides/classification-rules.md).

## The enrichment ladder

Riptide enriches each flow as well as the environment allows.
In the worst case a flow carries exactly what the packets said.

| Layer | Source | Needs |
| --- | --- | --- |
| 2, live | SNMP IF-MIB, reverse DNS | reachable agents and resolvers |
| 1.5, exporter-pushed | v9/IPFIX interface option records (`option interface-table`), v9/IPFIX application tables (`option application-table`, RFC 6759) | the exporter sending them, nothing on riptide's side |
| 1, static | operator mapping files (enrichment-entry `interfaces`, routing mapping) | a config file |
| 0.5, global databases | GeoIP mmdb files ([`riptide.geoip`](../reference/geoip.md)) | database files on disk |
| 0, packet | ifIndex numbers, exporter-sent AS numbers, addresses, next hop | nothing, always available |

Precedence is a per-field pin.
A field set in a static mapping overrides the live value, live sources fill the fields the file does not set, and packet data is the floor.
For AS numbers a nonzero exporter-provided value always wins: the routing mapping only fills zeros, and GeoIP databases sit below the routing mapping (exporter, then routing prefixes, then the GeoIP override, then the GeoIP databases).
Country and city come only from GeoIP.
A [`riptide.geoip.overrides`](../reference/geoip.md#manual-overrides) entry pins its set fields over whatever the databases resolve.

For interface fields, exporter-pushed option data and live SNMP share the work with per-field authority, after any static pin.
The interface name prefers the option record: IE 82 is exactly ifName, and pushed data is fresher than a poll.
The alias prefers SNMP ifAlias, because IE 83 (`interfaceDescription`) may carry ifDescr-style or ifAlias-style content depending on the vendor, so it only fills the alias when SNMP cannot.
The speed exists only in SNMP.
Cisco IOS-XR exporters send their interface table with descriptions only and no IE 82, so those flows get aliases without any SNMP configuration.

The floor extends into parsing.
An sFlow sample whose raw packet header cannot be decoded (truncated by the sampler, non-IP payload) still becomes a flow carrying the sample-level data, bytes, packets and interfaces, with the undecodable fields absent.
Undecodable is not an error.

## Static interface mapping

An [enrichment entry](../reference/exporter-enrichment.md) may carry its own interface table.
That is the middle rung, for devices without reachable SNMP:

```yaml
riptide:
  exporters:
    core-router:
      address: 10.20.30.0/24
      interfaces:
        "10": { name: eth0, alias: "Uplink to AS64500", high-speed: 10000 }
        "12": { name: eth2 }
```

With the device also inside a credentialed [agent range](../reference/agent-configuration.md), pinned fields win and SNMP fills the rest, for example a pinned `alias` with live `name` and `high-speed`.
`high-speed` is Mbit/s, matching `ifHighSpeed`.

## SNMP interface data

When a flow's exporter falls inside a credentialed [agent range](../reference/agent-configuration.md) with SNMP configuration, the numeric `ifIndex` values carried by the flow (`INPUT_SNMP` and `OUTPUT_SNMP` in NetFlow v9, `ingressInterface` and `egressInterface` in IPFIX) are resolved against the device's IF-MIB.
The column-by-column mapping is on the [enrichment reference](../reference/enrichment.md#if-mib-columns).

### Interface tables are polled, not looked up

Riptide never issues SNMP on the flow path.
An exporter is registered the first time a flow arrives from it, its whole interface table is then walked on a schedule, and enrichment reads the resulting snapshot.

Load on a device's SNMP agent is therefore a function of the poll schedule, not of how many distinct interfaces its flows reference.
Before this design each `(exporter, ifIndex)` pair cost its own full table walk, so a busy device with many active interfaces was polled hardest, and walks for different interfaces on the same device could run at the same time.

Cadence (refresh and expiry) is per polling profile.
Profiles live under `riptide.snmp.polling.<name>` and are referenced from agent ranges in the inventory file.
The profile named `default` applies to every range that names none; without one, the built-in defaults apply (10 m refresh, 30 m expiry).
The fleet-level `riptide.snmp.poll.*` keys (pool width, deregistration, dead-endpoint back-off, the exporter bound) apply across all profiles; the [enrichment reference](../reference/enrichment.md#snmp-polling) lists them.

Refresh and expiry are two settings because they answer two questions.
Refresh is how fresh the data is kept.
Expiry is the absolute staleness bound, the backstop for `ifIndex` reassignment after a device reboot (RFC 2863).
A snapshot older than the refresh interval but inside the expiry window is still served, because an interface name from the previous cycle beats no interface name at all.
Setting expiry shorter than refresh makes enrichment blank between walks, and the collector warns at startup if you do.

Walks are spread across the refresh interval using a phase derived from the exporter's address, plus a small jitter.
The fleet does not arrive at the agent as one burst, and the phase is stable across restarts without any stored state.

Between an exporter's first flow and its first completed walk there is no snapshot, so those flows carry no SNMP-derived interface fields.
Static interface pins and exporter-pushed option data still apply, so enrichment degrades rather than fails.
That warmup window is expected behaviour, not a fault: it is the cost of never blocking flow processing on a network round trip.
A newly added interface likewise becomes visible at the next poll rather than within a minute.
An unresolvable `ifIndex` deliberately does not trigger an early walk, because that would put agent load back under the control of flow traffic.

Unreachable endpoints back off exponentially between `dead-endpoint-base-ms` and `dead-endpoint-ceiling-ms` instead of retrying at a fixed interval, because a walk against a dead agent holds a pool slot for its whole timeout.
Misses are not cached separately: an `ifIndex` absent from a polled snapshot is a known absence, so there is nothing to expire.

## Reverse-DNS hostnames

Source, destination and next-hop addresses are resolved to hostnames via PTR lookups, Netty-based and asynchronous.
It is off by default; **`riptide.enricher.hostnames.enabled`** turns it on.

## AS numbers and names

The static [routing mapping](../reference/routing.md) fills `srcAs` and `dstAs` when the exporter sent zeros (nonzero exporter values always win) and resolves AS names and organisations into `srcAsOrg` and `dstAsOrg`.

## Classification

Flows are classified by a rule engine that assigns an application name.
The rule source is any Spring resource location and defaults to the bundled `classification-rules.csv`; [Write a classification rule](../guides/classification-rules.md) has the format and the reload procedure.

Row order is the evaluation priority.
When several rules match a flow, which is common when a client's ephemeral port collides with another rule's registered port, the earliest matching row wins, in both directions of an omnidirectional rule.
In a custom ruleset, put specific rules (address plus port) above broad ones (port-only), or the broad row shadows them.

The decision-tree build a start or a reload pays grows faster than the ruleset does, so there is a supported ruleset size.
The number and the measurement behind it are in [Classification tree build cost](classification-build-cost.md#supported-ruleset-size).

### Application names from the exporter

An exporter that runs application recognition sends two things: an `applicationId` (IPFIX element 95 or NetFlow v9 field 95, RFC 6759) on every flow record, and an application table as option records that maps each id to a name and a description.
Cisco NBAR2 does this with `match application name` in the flow record and `option application-table` on the exporter, over IPFIX or NetFlow v9 alike.
Riptide consumes both, over both protocols.
A v9 exporter names the table's columns `APPLICATION NAME` and `APPLICATION DESCRIPTION` and scopes each row by the system rather than by the id; those rows feed the same table as the IPFIX `applicationName` rows.

The ladder for `application`, in order:

1. The record carries a non-zero `applicationId` and the exporter's table names it: `application` is that name and `applicationSource` is `exporter`.
2. Otherwise the classification rules run: a match sets `applicationSource` to `rules`.
3. Nothing matched: `application` is null and `applicationSource` is `none`.

An exporter that names a flow `unknown` has not classified it, so the rules answer instead and `applicationSource` says `rules`.
The unresolved meter does not move, because the exporter did answer.

Two columns on `flows` carry the evidence.
`applicationId` is the packed id, `engine << 24 | selector`, and `0` when the record carried none.
`applicationSource` is one of the three tokens above; `''` means the row predates the column.
The rollups carry the name but not the id or the source.

A non-zero id the table cannot name falls through to the rules and marks `enrichment.application.unresolved`.
That is normal for the first table refresh interval after a restart (Cisco defaults to 600 s `(unverified)`), and permanent on a device that exports ids without a table.
A Juniper SRX340 on Junos 24.4R1-S3.7 exports `applicationId` in its IPv4 template when the template carries `export-extension app-id`, writes `0` into every record, and sends no name table; the measurement is recorded in [#848](https://github.com/Riptide-Labs/riptide/issues/848).
Application names leave such a box only as AppTrack syslog (`APPTRACK_SESSION_CLOSE`), which riptide does not consume; consuming it is the work in that issue.
Until then an SRX is named by the rules rung, and the unresolved meter does not move, because `0` means "not sent".

The table's own meters follow the interface table's vocabulary: `enrichment.optionApplications.consumed`, `.skipped` (a named row with no usable id, or a name past the cap) and `.rejected` (an entry evicted because a scope hit its cap).
Retention is the interface table's setting, **`riptide.snmp.options.retention-ms`**; there is no separate key.
The per-scope cap is fixed at 16,384 ids, sized for a full NBAR2 protocol pack, and `enrichment.optionApplications.rejected` counts anything it evicts.
A name longer than 64 characters is not stored and counts under `enrichment.optionApplications.skipped`, because `application` is a rollup dimension and an exporter must not be able to fill it with arbitrary text.

Lookups try the exact exporter identity, address plus observation domain, and then any observation domain of the same address.
A domain that has a table of its own never borrows from another domain, so two exporting processes behind one address only share names when one of them sends no table at all.
A Catalyst 8000V sends its option tables under one observation domain and its flow records under another.
The fallback is what makes its names resolve, for interface names as well as application names.

### HTTP host and URI from Cisco AVC

A Cisco exporter configured with `collect application http host` and `collect application http uri statistics` sends two enterprise elements on its HTTP records: the HTTP host (PEN 9, element 12235) and the HTTP URI statistics (PEN 9, element 9357).
Riptide stores both, in the `httpHost` and `httpUri` columns of `flows`.
The layouts below were read from a Catalyst 8000V on IOS-XE 26.01.02, the reference capture that nl6 keeps under `testdata/cisco-avc/capture/`, and riptide's own fixture tests pin them against those bytes.
Another platform has not been checked.

The host element is not a bare hostname.
Every value starts with six bytes, the http application id `03 00 00 50` followed by the sub-application id `34 02`, and the hostname follows.
The prefix is a constant of the layout, http's own id and the host field's sub-application id, so riptide matches it and stores what follows; the prefix alone, which is what a record with no host carries, stores `''`.
A value that does not start with the prefix is stored as sent, so a platform that sent a bare hostname keeps it, and a platform with a different prefix shows control bytes at the start of the value rather than a hostname missing its first six characters.

The URI element is a sequence of pairs, each a URI terminated by a NUL byte and followed by a two-byte big-endian hit count, with no trailing delimiter.
The router records the first path segment only: `/api/v1` and `/api/login` both arrive as `/api`.
The reference capture carries one pair per record, because IOS-XE only binds this element on a monitor aged at transaction end.
One column holds one URI, so when a record carries several pairs riptide stores the URI with the highest count, the first on a tie.
A trailing fragment without its NUL or its count is ignored, and a field with no complete pair stores `''`.

Both elements ride the ingress record of a request only.
The response record of the same conversation carries the six-byte prefix alone and an empty URI field, and so does every record the L7 engine reclassified mid-connection, so those rows read `''` in both columns.
A panel that ranks hosts must therefore count request records rather than bytes: the response bytes sit on a row with no host.
`''` also means the exporter sent no such element, or the row predates the columns; the three cannot be told apart.

The exporter's application table carries a description beside each name, and riptide stores it in `applicationDescription` on every row the table named, that is where `applicationSource` is `exporter`.
A row the rules named, or nothing named, reads `''`.

Riptide does not model the third Cisco element on the same record, the connection id (PEN 9, element 12242).
It is logged as an undeclared element when the template arrives, which is the only signal that it is being dropped.
NetFlow v9 export of the HTTP elements is not parsed either.

## Locality

Source, destination and flow locality (private versus public address space) is derived for every flow without configuration.

## Clock correction

Exporter clocks lie.
sysUpTime arithmetic produces impossible timestamp orderings, and a device with broken NTP exports flows minutes in the past or future, invisible in any "last 15 minutes" dashboard window even though they arrive and persist fine.
Clock correction defends the flow's time columns with two mechanisms:

1. Ordering repair, on whenever the enricher is enabled: a flow claiming `firstSwitched` after `lastSwitched` is rebuilt anchored on the packet's export timestamp, preserving the flow's duration where the record allows.
2. Skew correction, opt-in through **`riptide.enricher.clock-correction.skew-threshold-ms`**: the export timestamp is compared against `receivedAt`, the collector's own clock. When the difference reaches the threshold, all of the flow's time columns (`timestamp`, `firstSwitched`, `deltaSwitched`, `lastSwitched`) are shifted by the negative skew, so the flow lands where it actually happened.

Every applied skew correction is recorded in the flow's `clockCorrection` column as the negated skew, so corrections are auditable per row and a skewed exporter is queryable directly:

```sql
SELECT exporterAddr, count() AS correctedFlows
FROM riptide.flows
WHERE clockCorrection != 0
GROUP BY exporterAddr
```

Expected output, on a collector running with the default threshold of `0`, where skew correction is off and no row carries a correction:

```text
```

Choose the threshold above your fleet's normal export delay.
Exporters typically run one to two active-timeout intervals behind `receivedAt`, often 60 to 90 s, so a threshold of 2 minutes corrects genuinely broken clocks without rewriting healthy jitter.
Skew correction trusts the collector's clock, so keep the riptide host NTP-synced, or the "correction" would skew every exporter by the collector's own error.
Fixing the device's NTP remains the real cure; this is the safety net that keeps the data usable until it lands.

## Open questions

- The 600 s default for the application table refresh on Cisco exporters is a vendor claim carried from the earlier page and was not verified against Cisco documentation.
- The `clockCorrection` query above was run against a collector with skew correction off, so its output is empty; no output from a collector with a threshold set has been captured.
