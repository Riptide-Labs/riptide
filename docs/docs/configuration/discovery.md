---
sidebar_position: 3.5
title: Dynamic exporter discovery
---

# Dynamic exporter discovery

Riptide can read its exporter list from a Prometheus HTTP service discovery endpoint instead of from the inventory file.
A site already running NetBox as its source of truth then maintains one device list rather than two.

Discovery is off until `riptide.discovery.url` holds a non-blank value.
A blank value (unset, or whitespace-only) is treated the same as no value at all: nothing discovery-related is created, and the inventory file stays the only source of exporter entries.
That equivalence is deliberate: a container image or a Helm template commonly exports every variable it knows about whether or not it has a value, and `RIPTIDE_DISCOVERY_URL=""` must not turn discovery on for an operator who never asked for it.

## What discovery owns

Discovery owns the `exporters` tree.
The inventory file keeps owning `snmp.agents`, because agent ranges are a handful of CIDRs that gain nothing from per-device discovery, and because SNMP enrichment would otherwise stop the moment discovery was enabled.

No entry ever has two possible sources.
Writing an `exporters` tree into the inventory file while `riptide.discovery.url` is set is refused with a message naming both locations, whether or not the endpoint can be reached. The check runs on every merge of the file with the endpoint, at boot and on every later poll, not only at startup.

### Decommissioning a fleet

The reloader refuses a file in which a previously populated tree is simply absent, because that is what a half-written file looks like. To empty one on purpose you write it as an explicit empty mapping, and that still holds with discovery enabled.

Both spellings work: the narrow `riptide.snmp.agents: {}`, and the broad `riptide: {}`. The broad one declares everything the file still owns to be empty, which with discovery enabled means the agent ranges; the exporters keep coming from the endpoint, because the file does not own that tree any more.

The third form documented for a file-only inventory, `exporters: {}`, is not available here. The file may not declare an exporters tree at all while discovery owns it, so writing one is refused rather than read as a decommission.

## Choosing a source

There are three, and `riptide.discovery.type` picks between them. Unset means `prometheus-sd`, so an existing deployment is unaffected.

| Type | Reads | Needs a NetBox plugin | Pages | Filters |
|---|---|---|---|---|
| `prometheus-sd` (default) | A Prometheus HTTP service discovery document | Yes, `netbox-plugin-prometheus-sd` | No | No |
| `netbox-api` | NetBox's own device API | No | Yes | Yes |
| `mapped-json` | Any JSON endpoint, by paths you write | No | If you say where the next link is | Whatever the endpoint accepts |

The first two read NetBox in most deployments and differ in what they speak to. The names say that rather than naming the product, because naming one of them "netbox" would suggest the other does not read NetBox.

Pick `netbox-api` if you cannot install a NetBox plugin, or if your inventory is large enough that fetching all of it on every poll is a cost you would rather not pay. The plugin endpoint disables pagination and supports no conditional requests, so every poll transfers a full serialization of every visible device.

Pick `prometheus-sd` if the plugin is already installed and working, or if your source of truth emits that format. It is a contract many producers emit, not a NetBox feature.

Pick `mapped-json` if your source of truth is something else entirely: a home-grown asset database, a CMDB, a DCIM that is not NetBox. You point it at the endpoint you already serve and say which fields hold the name and the address, instead of running a service to translate one into a format Riptide already knows.

Point `riptide.discovery.url` at whichever endpoint the type needs: the plugin's path for one, `/api/dcim/devices/` for another, your own for the third.

## Mapping your own endpoint

`mapped-json` reads four paths. Three are required; the fourth turns on paging.

| Key | Required | Meaning |
|---|---|---|
| `riptide.discovery.mapping.items` | yes | Where the array of devices is in the response |
| `riptide.discovery.mapping.name` | yes | Where the exporter name is, in one device |
| `riptide.discovery.mapping.address` | yes | Where the exporter address is, in one device |
| `riptide.discovery.mapping.next` | no | Where the link to the next page is. Unset means one request |

```yaml
riptide:
  discovery:
    url: https://assets.internal/api/devices
    type: mapped-json
    mapping:
      items: payload.inventory.nodes
      name: identity.fqdn
      address: net.mgmt.v4
      next: cursor.more
```

That configuration reads this:

```json
{
  "payload": {"inventory": {"nodes": [
    {"identity": {"fqdn": "edge-01.dc1"}, "net": {"mgmt": {"v4": "10.0.0.1"}}}
  ]}},
  "cursor": {"more": "https://assets.internal/api/devices?page=2"}
}
```

A missing required path fails startup naming the key, rather than turning up as an empty result at the first poll.

### What a path is, and what it is not

A path is dotted field names. `net.mgmt.v4` walks three fields and takes what it finds.

That is the whole language. There are no transforms, no defaults, no conditionals, no concatenation, no indexing and no wildcards, and there is no template or expression syntax. Those were considered and rejected: an expression language fed from a configuration file is a code-execution surface, which is the thing Riptide resolves every credential through a [secret reference](secret-references.md) to avoid.

A dot separates field names and nothing else, so a field whose name contains a dot cannot be reached.

**A value is used as found, which means a value that is not usable cannot be made usable.** The case you are most likely to meet is an address served with host bits under a prefix length, `10.0.0.1/24`. An exporter address is matched against the source address of a flow, so that cannot be used, and those devices are skipped and counted on `discovery.skipped` like any other unusable entry.

When *nothing* was usable for that reason, the failure says so: which path, which values, and that `netbox-api` exists for NetBox. That is the case worth naming, because otherwise the only message is that your endpoint yielded nothing, which reads as a filter or permission mistake. A fleet where only some devices carry a prefix publishes the rest and reports the skipped count on the gauge.

A network **range** is not this problem. `10.0.0.0/24` with no host bits is a legal exporter entry, because the matcher is a prefix trie, and it is used as found.

If that endpoint is NetBox, use `netbox-api`, which knows to strip it. If it is your own, serve the address without the prefix.

A path that matches nothing on a particular device is not an error: that device is skipped and counted, like any device with no usable address.

### Narrowing what NetBox returns

`riptide.discovery.filter` is passed to NetBox in its own query terms, so you can develop it against NetBox directly and paste in what works:

```yaml
riptide:
  discovery:
    type: netbox-api
    url: https://netbox.example.com/api/dcim/devices/
    filter: status=active&role=leaf&role=spine
```

It is read by `netbox-api` and `mapped-json`, which append it to the endpoint's query. The service discovery reader takes its filtering from whatever produced the document.

A filter matching nothing is refused rather than publishing an empty exporters tree, which is the same rule an empty endpoint answer gets. If discovery stops updating right after you add a filter, that is the first thing to check.

The walk requests a stable ordering, so a device added while it is in progress appends rather than shifting the pages still to be read.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `riptide.discovery.url` | unset | The endpoint. Unset or blank disables discovery. Point it at whichever endpoint the type needs. |
| `riptide.discovery.type` | `prometheus-sd` | Which source to read: `prometheus-sd`, `netbox-api` or `mapped-json`. An unrecognised value fails startup, naming what is accepted. |
| `riptide.discovery.filter` | unset | Narrows what the endpoint returns, in its own query terms. Read by `netbox-api` and `mapped-json`. |
| `riptide.discovery.mapping.items` | unset | Where the array of devices is. Required by `mapped-json`; see [Mapping your own endpoint](#mapping-your-own-endpoint). |
| `riptide.discovery.mapping.name` | unset | Where the exporter name is in one device. Required by `mapped-json`. |
| `riptide.discovery.mapping.address` | unset | Where the exporter address is in one device. Required by `mapped-json`. |
| `riptide.discovery.mapping.next` | unset | Where the link to the next page is. Read by `mapped-json`; unset means one request. |
| `riptide.discovery.token` | unset | Credential, as a [secret reference](secret-references.md). Resolved on every poll, so rotating it takes effect on the next one with no restart. A reference that stops resolving fails the poll rather than sending an unauthenticated request. |
| `riptide.discovery.auth-scheme` | `Token` | Paired with the token in the `Authorization` header. NetBox expects `Token`, not `Bearer`. With a token set, a blank value is refused at startup naming the key, rather than treated as unset like the URL: it would send an `Authorization` header with no scheme, which an endpoint rejects with nothing naming the scheme. Leave the key out to get the default. With no token set the scheme is never read, so a blank value is harmless and startup is unaffected. |
| `riptide.discovery.interval` | `60s` | Poll interval. Zero or negative disables the watcher entirely; see [Startup](#startup). |
| `riptide.discovery.timeout` | `10s` | Bounds the connect, each read, and the whole response. |
| `riptide.discovery.address-labels` | `__meta_netbox_primary_ip4,__meta_netbox_primary_ip6` | Labels consulted in order for an address. Customising it while `type` is `netbox-api` is refused at startup: that source emits these two names itself, so a different list would match nothing and every device would be skipped. |

Example against the NetBox service discovery plugin:

```yaml
riptide:
  discovery:
    url: https://netbox.example.com/api/plugins/prometheus-sd/devices/
    token: vault://secret/netbox#token
    interval: 60s
  inventory:
    file: /etc/riptide/inventory.yaml
```

The inventory file then carries agent ranges only:

```yaml
riptide:
  snmp:
    agents:
      "10.20.0.0/16":
        credentials: corp-v3
        polling: default
```

## How a target becomes an exporter

The exporter name comes from the `__meta_netbox_name` label when it is present, and otherwise from the target itself with any trailing port removed.

The address comes from the first label in `address-labels` that is present **and** parses as a strict address (the same parser the inventory loader uses for agent ranges).
A label present but malformed (`not-an-ip`) is skipped as if it were absent, and the next label in the list is tried.
When no label qualifies, the target itself is used as the address, but only if the target parses as a strict address too; a NetBox device's target is its name, so that fallback never fires for NetBox and the entry is skipped instead.

An entry with a blank name, or with no address from either a label or the target, is never guessed at.
It is skipped and counted on the `discovery.skipped` gauge.
Two entries carrying the same name and the same address are not a collision; they collapse into one entry.
Two entries carrying the same name with *different* addresses are refused, and every collision is named at once.
Two entries carrying *different* names with the same address are refused the same way.

That second-stage fallback is what makes non-NetBox producers work.
Generic service discovery puts a real address in the target, where NetBox puts a device name.

Note that a NetBox device's target is its **name**, not an address, and that every IP address NetBox's service discovery plugin emits already has its CIDR mask stripped.
You do not need to strip one yourself.

## What gets refused

Riptide keeps the running inventory rather than publishing a doubtful one.

- A device with no usable address is skipped and counted, never guessed at. The count is on the `discovery.skipped` gauge.
- Two devices resolving to the same exporter name with different addresses are refused, and every collision is named at once. NetBox enforces device-name uniqueness per site, not globally, so two sites each holding a `sw1` is ordinary.
- Two devices with different names resolving to the same address are refused the same way, naming every colliding address and the devices claiming each. NetBox enforces uniqueness on device name, not on primary IP, so an HA pair or a virtual-chassis member pair sharing one primary IPv4 is ordinary. One device appearing twice with the same name and the same address is not a collision: it is deduplicated into one entry.
- A response that parses but yields no entries is refused. A filter typo or a permission change must not be able to wipe every exporter name.
- A response that is not a Prometheus HTTP service discovery document (not a JSON array, a non-object entry, a non-string target, a non-string label value) is refused, naming the endpoint.
- An `exporters` tree already present in the inventory file is refused on every merge, at boot and on every later poll, naming both the file and `riptide.discovery.url`.

A 404 is treated as absence: the last good inventory keeps serving, `inventory.reload.stale` goes to 1, and the log warns once per episode rather than on every poll.

A **missing inventory file** is absence too, exactly as it is with discovery off: the cycle is skipped, the log warns once, the last good inventory keeps serving, and `inventory.reload.failures` does not move.
That covers both a file deleted after boot and the window an `rm` plus `mv` replacement opens.
A file that is present but unreadable, a permission denial for example, stays a counted failure: an operator told to make a file reappear that is already there has been sent to the wrong place.

## How a failure names itself

With discovery on the inventory is one document composed from two sources, so failures name both:

```
Inventory source /etc/riptide/inventory.yaml + https://netbox.example.com/api/dcim/devices/ carries problems in 1 entry:
```

"Source" rather than "file", and the same for the clauses that refer back to it later in a message.
Which half to look at is what the two names are for: a problem in an agent range is the file's, a problem in an exporter is the endpoint's, and the entry named in the line tells you which.
With discovery off the same failure reads `Inventory file /etc/riptide/inventory.yaml ...`, unchanged.
One phrase inside a problem line changed on both paths rather than being dispatched per source: a stray or non-string key at the top level is now reported under "the document root" rather than "the file root", because with discovery on that root belongs to a composed document.

This matters for the remediation as much as the noun.
A message about a partly written document tells you to write the file atomically only when the document is a file; with discovery on it says the next poll composes it again, because no `mv` you run fixes a response that was read short.

## Startup

An endpoint that cannot be **reached** at boot (a refused connection, a timeout, or a 404) does not fail startup.
Riptide warns and serves the inventory file's trees with no `exporters` tree at all.
A collector that refuses to start because NetBox is down is worse than one that starts without device names.

Every other discovery failure still fails boot: an endpoint that answers with something that is not a service discovery document, a name collision, an empty answer, and an `exporters` tree already present in the inventory file. Those are configuration an operator has to see, not an endpoint that is temporarily down.

After boot, a poll that fails for any reason (unreachable or invalid) keeps the last good inventory serving; nothing publishes a partial or degraded document past boot.

Healing a degraded boot needs a working reload schedule.
With `riptide.discovery.interval` at its default or any positive value, the watcher retries on that schedule, `inventory.reload.stale` reads 1 until the first successful poll publishes the discovered exporters, and the boot warning names that interval.
Set to zero or a negative duration, the watcher never starts at all: no reload is scheduled and no `inventory.reload.stale` gauge is even registered, so the boot warning tells the operator the exporters stay missing until a restart.
A main-config reload that rotates a credential heals it too, without a restart, because that path re-reads the endpoint on its own regardless of `riptide.discovery.interval`.
It heals it only while the endpoint is reachable: the rotation's rebuild reads the endpoint strictly, so with the endpoint still down the rebuild fails, the rotation is parked as pending, and nothing is published.
While a rotation is pending, that retry runs once per **config** reload cycle, so the endpoint is polled at `riptide.config.reload-interval` rather than at `riptide.discovery.interval`, and each attempt can take up to `riptide.discovery.timeout`.
A `riptide.config.reload-interval` of a few seconds therefore retries a down endpoint every few seconds until the rotation lands.
Nothing else does.

## Metrics

| Metric | Meaning |
|---|---|
| `discovery.targets` | Exporter entries in the inventory **currently serving**. It never describes a candidate that was composed and then refused, so it is safe to alert on. |
| `discovery.skipped` | Entries the **most recent render** dropped for want of a usable address, whether or not the candidate built from it was published. |
| `inventory.reload.successes` | Shared with the inventory file watcher. |
| `inventory.reload.failures` | A refused or unreachable poll counts here. With discovery on, content validation runs on every fetch, so an endpoint that keeps answering with the same bad content is counted, and logged, on every poll rather than once. |
| `inventory.reload.stale` | The endpoint's document differs from what is serving. |

The two discovery gauges answer different questions and will legitimately disagree while a candidate is being refused: the first describes what is serving, the second describes what the endpoint last offered. That is deliberate. A device the endpoint keeps returning without a usable address is worth seeing precisely while the candidate around it is being rejected, which is when it explains the most.

## Limits

An endpoint served by an internal certificate authority needs that authority in `riptide.http.ca-bundle`; see [Outbound TLS](outbound-tls.md).
There is no way to disable certificate verification, and that page says what to do instead.

The NetBox service discovery plugin disables pagination and supports no conditional requests, so every poll transfers a full serialization of every visible device.
Bound it with NetBox filters, for example `?status=active&role=leaf&role=spine`.
