---
title: Discovery reference
sidebar_position: 4
description: Every riptide.discovery.* key with its default, the three sources compared, the mapping path rules, the walk limits, the two gauges, and every message discovery can raise.
---

# Discovery reference

Discovery reads the `exporters` tree from an external inventory instead of from the inventory file.
It is off until **`riptide.discovery.url`** holds a non-blank value.
How the composed inventory behaves is on [How discovery composes the inventory](../architecture/discovery.md); the per-source procedures are the four `Discover exporters from ...` guides.

## Sources

| Type | Reads | Needs installing | Pages | Filters |
| --- | --- | --- | --- | --- |
| **`prometheus-sd`** (default) | A Prometheus HTTP service discovery document | Nothing, unless the producer is NetBox (then the [`netbox-plugin-prometheus-sd`](https://github.com/FlxPeters/netbox-plugin-prometheus-sd) plugin) | No | No, the producer filters |
| **`netbox-api`** | NetBox's own device API (`/api/dcim/devices/`) | Nothing | Yes, follows `next` to completion | Yes, `riptide.discovery.filter` in NetBox's query terms |
| **`mapped-json`** | Any JSON endpoint, by paths you write | Nothing | Only when `mapping.next` is set | Whatever the endpoint accepts, passed through `riptide.discovery.filter` |

The names say what is being spoken to, not which product is behind it.
Guides: [NetBox](../guides/discovery-netbox.md), [Prometheus service discovery](../guides/discovery-prometheus-sd.md), [Nautobot](../guides/discovery-nautobot.md), [any JSON endpoint](../guides/discovery-mapped-json.md).

## Settings

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.discovery.url`** | URL | unset | The endpoint. Unset, empty or whitespace-only disables discovery and creates nothing discovery-related. A value that is not a URL fails startup naming the key. |
| **`riptide.discovery.type`** | string | `prometheus-sd` | `prometheus-sd`, `netbox-api` or `mapped-json`, case-insensitive. Blank is the default. Any other value fails startup naming the accepted values. |
| **`riptide.discovery.filter`** | string | unset | Appended to the endpoint's query, in the endpoint's own terms (`status=active&role=leaf`). Read by `netbox-api` and `mapped-json`. `prometheus-sd` ignores it; the producer filters. |
| **`riptide.discovery.token`** | [secret reference](secret-references.md) | unset | Credential sent as `Authorization: <auth-scheme> <token>`. Resolved on every request, so each page of a paged walk resolves it again and a rotation applies on the next request with no restart. A reference that stops resolving fails the poll rather than sending an unauthenticated request. |
| **`riptide.discovery.auth-scheme`** | string | `Token` | The scheme in front of the token. NetBox and Nautobot expect `Token`, not `Bearer`. With a token set, a blank value fails startup naming the key. With no token set the scheme is never read and a blank value is harmless. |
| **`riptide.discovery.interval`** | duration | `60s` | Poll interval of the inventory watcher. Zero or negative never starts the watcher: no reload, no `inventory.reload.stale` gauge, and a degraded boot stays degraded until a restart. |
| **`riptide.discovery.timeout`** | duration | `10s` | Bounds the connect, each read, and the whole response of every request. |
| **`riptide.discovery.address-labels`** | list of strings | `__meta_netbox_primary_ip4,__meta_netbox_primary_ip6` | Labels consulted in order for an address by `prometheus-sd`. Customising it while `type` is `netbox-api` fails startup: that source emits exactly these two names. |
| **`riptide.discovery.mapping.items`** | path | unset | `mapped-json`: where the array of devices is. Unset means the response is itself the array. |
| **`riptide.discovery.mapping.name`** | path | unset | `mapped-json`: where the exporter name is in one device. Required by `mapped-json`; unset fails startup naming the key. |
| **`riptide.discovery.mapping.address`** | path | unset | `mapped-json`: where the exporter address is in one device. Required by `mapped-json`; unset fails startup naming the key. |
| **`riptide.discovery.mapping.next`** | path | unset | `mapped-json`: where the link to the next page is. Unset means one request. |

All four `mapping.*` keys are read only when `type` is `mapped-json`.
The main configuration file is the place for these keys; the inventory file (`riptide.inventory.file`) may not carry an `exporters` tree while discovery is on.

## Mapping paths {/* #mapping-your-own-endpoint */}

A path is dotted field names: `net.mgmt.v4` walks three fields and takes what it finds.

| Rule | Detail |
| --- | --- |
| Separator | A dot separates field names and nothing else. A field whose name contains a dot cannot be reached. |
| Whitespace | Each segment is stripped, so `net. mgmt.v4` is `net.mgmt.v4`. An inner space survives, because a field name may contain one. |
| Empty segment | `a..b`, a leading dot or a trailing dot fails startup: `<key> is not a usable path`. |
| Transforms | None. No defaults, conditionals, concatenation, indexing, wildcards, templates or expressions. |
| Value | Used as found. An address carrying a prefix length (`10.0.0.1/24`) is skipped and counted on `discovery.skipped`; a range with no host bits (`10.0.0.0/24`) is a legal exporter entry. |
| Miss | A device whose `name` or `address` path matches nothing is dropped by the source and not counted. Only an address that is present but unusable reaches `discovery.skipped`. |
| `next` | Absolute or relative. A relative link is resolved against the page it came from. It must stay on the same origin (scheme, host and port) as `riptide.discovery.url`, because the credential is sent with every page. |

## Limits

| Limit | Value | On reaching it |
| --- | --- | --- |
| Devices per walk (`netbox-api`, `mapped-json`) | 100,000 | The poll fails; the message names `riptide.discovery.filter`. The last good inventory keeps serving. |
| Pages per walk | 10,000 | The poll fails; the message names the endpoint. |
| Bytes per response | 32 MiB | The read is refused. |
| Request time | `riptide.discovery.timeout` for connect, each read and the whole response | The poll fails. |
| Certificate verification | Always on | An internal CA goes in **`riptide.http.ca-bundle`**, see [Outbound TLS](outbound-tls.md). There is no way to disable verification. |

The NetBox service discovery plugin disables pagination and supports no conditional requests, so every `prometheus-sd` poll against it transfers a full serialization of every visible device.
Bound it with NetBox filters on the plugin side, for example `?status=active&role=leaf&role=spine`.

## Metrics

| Metric | Type | Meaning |
| --- | --- | --- |
| **`discovery.targets`** | gauge | Exporter entries in the inventory currently serving. Never describes a candidate that was composed and then refused, so it is safe to alert on. |
| **`discovery.skipped`** | gauge | Entries the most recent render dropped for want of a usable address, whether or not the candidate built from it was published. |
| **`inventory.reload.successes`** | counter | Shared with the inventory file watcher. |
| **`inventory.reload.failures`** | counter | A refused or unreachable poll. With discovery on, content validation runs on every fetch, so an endpoint that keeps answering with the same bad content counts, and logs, on every poll. |
| **`inventory.reload.stale`** | gauge | 1 while the endpoint's document differs from what is serving, including after a degraded boot. Registered only with a positive `riptide.discovery.interval`. |
| **`inventory.reload.dead`** | gauge | 1 when the watcher's schedule stopped and will not run again. |

The two discovery gauges disagree while a candidate is being refused: `discovery.targets` describes what is serving, `discovery.skipped` what the endpoint last offered.
That is when the skipped count explains the most.
Dots become underscores at `/metrics` (`discovery_targets`), see [Metrics reference](metrics.md).

## Messages

`<endpoint>` stands for the redacted `riptide.discovery.url`; `<file>` for the inventory file path.
A message that fails startup is also, after boot, a counted reload failure that keeps the last good inventory serving.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `riptide.discovery.type is not a source this collector knows: '<value>'. Accepted values are 'prometheus-sd', 'netbox-api', 'mapped-json'.` | Typo in `type` | Use one of the three values. |
| `riptide.discovery.url is not a usable URL: '<url>' (<reason>)` | The value is not a URL | Fix the URL. A blank value is not this error; it turns discovery off. |
| `riptide.discovery.auth-scheme must not be blank when riptide.discovery.token is set: ...` | An exported-but-empty `RIPTIDE_DISCOVERY_AUTH_SCHEME` | Set it to `Token`, or unset it to get that default. |
| `riptide.discovery.address-labels cannot be customised while riptide.discovery.type is 'netbox-api': this source emits [__meta_netbox_primary_ip4, __meta_netbox_primary_ip6] and the renderer must read the same names. ...` | Labels customised for a `prometheus-sd` producer, then the type switched | Remove `address-labels`, or use `prometheus-sd`. |
| `riptide.discovery.mapping.name must name a field: it is not set.` (also `mapping.address`) | `mapped-json` without a required path | Set the path. |
| `<key> is not a usable path: '<path>'. A dot separates field names, so an empty segment means a field with no name, which nothing can match.` | `a..b` or a trailing dot in a mapping path | Fix the path. |
| `riptide.discovery.url and riptide.discovery.filter do not combine into a usable URL: '<url>' + '<filter>'` | A filter that does not survive as a query string | Fix the filter. |
| `<file> declares an 'exporters' tree while riptide.discovery.url is set. Discovery owns the exporters tree and the inventory file owns snmp.agents, so an entry can never have two possible sources. Remove the exporters tree from the file, or unset riptide.discovery.url.` | Both sources define exporters | Do one of the two. Checked on every merge, at boot and on every poll. |
| `<endpoint> yielded no exporter entries (<n> entries were skipped for want of a usable address). Keeping the running inventory: a source of truth that answers with nothing is more often a filter or permission mistake than an emptied fleet.` | Empty answer, a filter matching nothing, a permission change, or every device skipped | Check the filter and the token first. If discovery stopped updating right after a filter was added, this is why. |
| `<endpoint> returned <n> exporter name(s) claimed by more than one entry. Exporter names are inventory keys, so a collision would silently drop every claimant but one. NetBox enforces device-name uniqueness per site, not globally.` followed by one `  name -> claimants` line per collision | Two devices with one name and different addresses | Rename one, or filter one out. Every collision is named at once. |
| `<endpoint> returned <n> exporter address(es) claimed by more than one entry. An exporter address is what a flow is matched on, so two entries sharing one with no observation domain are ambiguous and the loader refuses the whole document. NetBox enforces uniqueness on device name, not on primary IP.` plus one line per collision | Two devices with different names and one primary IP (an HA pair, a virtual-chassis pair) | Give one of them a different primary IP in the source, or filter one out. One device appearing twice with the same name and address is not a collision and is deduplicated. |
| `<endpoint> did not answer with a JSON array. Prometheus service discovery is a bare array of {targets, labels} objects, with no enclosing envelope.` | `prometheus-sd` pointed at a NetBox API page or another envelope | Use `netbox-api` for `/api/dcim/devices/`, or point at the plugin endpoint. |
| `<endpoint>: entry <i> is not an object` / `has no 'targets' array` / `has a non-string target` / `has a 'labels' field that is not an object` / `label '<name>' is not a string` | A producer that does not follow the Prometheus contract | Fix the producer. Label values are strings; a number is not coerced. |
| `<endpoint> did not answer with a NetBox device page. Expected an object carrying a 'results' array; a bare array is the Prometheus service discovery shape, which is riptide.discovery.type 'prometheus-sd' rather than 'netbox-api'.` | `netbox-api` pointed at the plugin endpoint | Point at `/api/dcim/devices/`, or switch the type. |
| `<endpoint> did not answer with an array at riptide.discovery.mapping.items = '<path>': found <what>. The path names where the devices are in the response; leave it unset if the response is itself the array.` (or `at the response root (riptide.discovery.mapping.items is unset)`) | `items` points at something that is not an array, or is missing when the response is an envelope | Fix or unset `items`. |
| `<endpoint> mapped <n> address(es) that carry a prefix length, which cannot be used as an exporter address: <examples>. The path language has no transform to remove it (riptide.discovery.mapping.address = '<path>'). ...` | Every mapped address carries a prefix length | Map a field serving the bare host (Nautobot: `primary_ip4.host`), serve the address without the prefix, or use `netbox-api` if the endpoint is NetBox. Raised only when nothing was usable; a fleet where only some devices carry a prefix publishes the rest and counts them on `discovery.skipped`. |
| `<endpoint> served more than 10000 pages. An endpoint that offers a next page forever, including an empty one, would otherwise be walked forever.` | A `next` link that never ends | Fix the endpoint's paging. |
| `<endpoint> returned more than 100000 devices. Narrow it with riptide.discovery.filter rather than reading an unbounded inventory on every poll.` | Unfiltered inventory over the bound | Add a filter. |
| `<endpoint> gave a 'next' page on a different origin (<scheme>://<authority>). The API token is sent with every page, so the walk stops rather than following it. Check whatever sets the forwarded host in front of it.` | A reverse proxy rewriting `next` to another host or scheme | Fix the forwarded host in front of the endpoint. |
| `<endpoint> gave a 'next' page link that is not a usable URL: '<link>'` | A `next` value that is not a URL | Fix `mapping.next` or the endpoint. |
| `<endpoint>'s response is not valid JSON: <reason>` | An HTML error page or truncated body served as 200 | Check the endpoint and any proxy. |
| `<endpoint> answered HTTP <n>, not 200` | Wrong token, wrong scheme (403), a 5xx | Check the token and `auth-scheme`. A 404 is not this message; it is absence. |
| `<endpoint> could not be read: <reason>` | Connection refused, timeout, TLS failure | At boot this degrades instead of failing; see the next row. |
| `WARN Boot could not reach <endpoint>: <reason>. Serving the inventory file's trees with no discovered exporters. A reload retries every <interval> and publishes them when it succeeds; inventory.reload.stale reads 1 until then.` | Endpoint down, refused, timed out or 404 at boot | Wait for the retry, or restart once it is back. With a non-positive interval the clause reads `No reload is scheduled and no inventory.reload.stale gauge is registered, so the exporters stay missing until a restart: set riptide.discovery.interval to a positive duration to have the endpoint retried.` |
| `Inventory source <file> + <endpoint> carries problems in <n> entry:` followed by one `  - ` line per problem | A problem in either half of the composed document | An agent-range problem is the file's, an exporter problem is the endpoint's; the entry named in the line tells you which. |

A 404 from the endpoint is absence, not a failure: the last good inventory keeps serving, `inventory.reload.stale` goes to 1, and the log warns once per episode.
A missing inventory file is absence too and moves no counter; a file that is present but unreadable is a counted failure.
