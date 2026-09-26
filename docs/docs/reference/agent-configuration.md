---
sidebar_position: 2
title: SNMP agents
description: Credential sets, polling profiles, the inventory file's agent ranges, fleet-wide poll limits, hot reload and every error the loader raises.
---

# SNMP agent reference

Which devices riptide polls for interface names, and how it talks to them.
Credential sets and polling profiles live in the main configuration.
Agent ranges live in the inventory file and reference both by name.
[Dynamic discovery](discovery.md) supplies exporter entries only and never touches agent ranges.

## Settings

Credential sets and polling profiles are maps keyed by a name of your choice.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.inventory.file`** | path | unset | The inventory file. Unset means no agent ranges and, with discovery off, no enrichment entries. A set path that cannot be read fails startup. The file's contents are read directly, never property-bound, so agent ranges cannot be supplied through environment variables or `spring.config.import`. |
| **`riptide.snmp.credentials.<name>.version`** | `v1`, `v2c`, `v3` | required | Selects the community or USM shape below. |
| **`riptide.snmp.credentials.<name>.community`** | [secret reference](secret-references.md) | required for `v1` and `v2c` | Community string. Forbidden on `v3`. |
| **`riptide.snmp.credentials.<name>.security-name`** | string | required for `v3` | USM user name. Forbidden on `v1` and `v2c`. |
| **`riptide.snmp.credentials.<name>.auth-protocol`** | see [protocol values](#protocol-values) | unset | Set together with `auth-passphrase` or not at all. |
| **`riptide.snmp.credentials.<name>.auth-passphrase`** | secret reference | unset | Resolved at every poll. |
| **`riptide.snmp.credentials.<name>.priv-protocol`** | see protocol values | unset | Set together with `priv-passphrase`, and only when auth is set. |
| **`riptide.snmp.credentials.<name>.priv-passphrase`** | secret reference | unset | Resolved at every poll. |
| **`riptide.snmp.polling.<name>.refresh-interval`** | duration | `PT10M` | Walk cadence. Positive, at most `PT24H`. |
| **`riptide.snmp.polling.<name>.snapshot-expiry`** | duration | `PT30M` | How long the last walked snapshot keeps serving after refreshes stop succeeding. Positive, at most `PT24H`. Shorter than the refresh interval logs a warning at startup. |
| **`riptide.snmp.polling.<name>.timeout`** | int, milliseconds | `500` | Per-request timeout. Positive. |
| **`riptide.snmp.polling.<name>.retries`** | int | `1` | Per-request retries. Zero or more. |
| **`riptide.snmp.polling.<name>.collect`** | list of strings | empty | Collection names this profile walks for counters, in addition to interface-name enrichment. `if-mib-interfaces` is the only accepted value; an unknown name fails the bind at startup. Non-empty makes `refresh-interval` the counter interval and the walk budget 80 percent of it: `timeout x (retries + 1)` must fit inside that budget. Settings, series and messages are on the [SNMP metrics reference](snmp-metrics.md). |
| **`riptide.snmp.poll.pool-width`** | int | `4` | Walks in flight across the whole fleet for agents whose last walk succeeded. Per-agent concurrency is always one. A walk takes no thread while it waits on the agent. |
| **`riptide.snmp.poll.suspect-pool-width`** | int | `8` | Walks in flight for agents whose last walk failed. Kept apart from `pool-width`, so dead agents waiting out their timeouts cannot hold back healthy ones. A due walk that finds neither budget free waits for the next tick and is counted on `snmp.poller.deferred`. |
| **`riptide.snmp.poll.deregister-after`** | int | `3` | Refresh intervals of silence after which a flow-registered agent stops being polled. An entry registered from the inventory because it is `poll: always` is never deregistered for silence; only removing or disabling the entry stops it. |
| **`riptide.snmp.poll.dead-endpoint-base-ms`** | long, milliseconds | `60000` | First retry delay after a failed walk. Doubles on every further failure. |
| **`riptide.snmp.poll.dead-endpoint-ceiling-ms`** | long, milliseconds | `1800000` | Longest retry delay for an agent that keeps failing. |
| **`riptide.snmp.poll.max-exporters`** | int | `4096` | Registered agents, flow-registered and `poll: always` alike. At the cap a new flow-registered exporter is rejected and counted on `snmp.poller.rejectedLookups`; nothing is evicted. If the inventory's `poll: always` entries alone exceed this cap, none of them is registered, and the refusal is logged and counted on `snmp.poller.inventoryRefused`. |

A range that names no profile uses the profile called exactly `default`: yours if you define one, otherwise the built-in values above.
The USM security level follows from the fields you set: none for noAuthNoPriv, auth for authNoPriv, both for authPriv.
The engine ID is discovered at runtime and never configured.

### Retired keys

| Key | Effect when set |
| --- | --- |
| `riptide.snmp.poll.refresh-interval-ms`, `riptide.snmp.poll.snapshot-expiry-ms` | Startup fails. Cadence lives on polling profiles since 0.9. |
| `riptide.snmp.config.definitions.*` | Ignored, logged as an error at startup. Move the sets to `riptide.snmp.credentials.<name>` and the devices to the inventory file. |
| `riptide.snmp.cache.retention-ms` | Ignored, logged as a warning. It used to set the walk cadence. To size the exporter option table use `riptide.snmp.options.retention-ms`. |

### Protocol values

| Setting | Values |
| --- | --- |
| **`auth-protocol`** | `md5`, `sha1`, `hmac128sha224`, `hmac192sha256`, `hmac256sha384`, `hmac384sha512` |
| **`priv-protocol`** | `des`, `_3des`, `aes128`, `aes192`, `aes256`, `aes192with3DESKeyExtension`, `aes256with3DESKeyExtension` |

Example:

```properties
riptide.inventory.file=/etc/riptide/inventory.yaml

riptide.snmp.credentials.corp-v3.version=v3
riptide.snmp.credentials.corp-v3.security-name=monitoring
riptide.snmp.credentials.corp-v3.auth-protocol=hmac192sha256
riptide.snmp.credentials.corp-v3.auth-passphrase=vault://secret/snmp/corp#authPassphrase
riptide.snmp.credentials.corp-v3.priv-protocol=aes256
riptide.snmp.credentials.corp-v3.priv-passphrase=vault://secret/snmp/corp#privPassphrase

riptide.snmp.credentials.legacy-v2c.version=v2c
riptide.snmp.credentials.legacy-v2c.community=env://RIPTIDE_SNMP_COMMUNITY

riptide.snmp.polling.brisk.refresh-interval=PT1M
riptide.snmp.polling.brisk.snapshot-expiry=PT30M
riptide.snmp.polling.slow.refresh-interval=PT30M
riptide.snmp.polling.slow.snapshot-expiry=PT90M
riptide.snmp.polling.slow.timeout=3000
riptide.snmp.polling.slow.retries=2
```

A credential value without a scheme is a plain literal.
Nothing stops that, and nothing excuses it; see [secret references](secret-references.md) for the schemes and when each is re-read.

## Inventory file

```yaml
# /etc/riptide/inventory.yaml
riptide:
  snmp:
    agents:
      "10.20.30.7":
        credentials: corp-v3
      "10.20.40.0/24":
        credentials: corp-v3
        polling: brisk
      "10.20.30.8":
        credentials: legacy-v2c
        port: 1161
      "10.99.0.0/24":
        enabled: false
```

Expected output at startup:

```text
org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml: 4 agent ranges, 0 enrichment entries
```

The `exporters` tree of the same file holds [enrichment entries](exporter-enrichment.md).
Every key under `riptide`, `riptide.snmp` and each agent range is checked; an unknown key fails the load.

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| **`credentials`** | name of a credential set | none | Without it the range matches but is never polled. |
| **`polling`** | name of a polling profile | `default` | |
| **`enabled`** | unquoted `true` or `false` | `true` | `false` carves the range out of a wider one: matched, not polled. |
| **`port`** | int, 1 to 65535 | `161` | |

### Address forms

The map key is a single host or a CIDR prefix, IPv4 or IPv6.

| Written | Result |
| --- | --- |
| `10.20.30.7`, `2001:db8::7` | accepted |
| `10.20.40.0/24`, `2001:db8::/64` | accepted |
| `10.0.1.5/24` | rejected: host bits set; the message offers `10.0.1.0/24` or `10.0.1.5` |
| `010.1.1.1` | rejected: leading zeros |
| `10.1` | rejected: `inet_aton` shorthand |
| `10.0.0.*`, `10.0.0.0/255.255.255.0` | rejected: wildcard or netmask spelling; the message gives the CIDR form |
| `fe80::1%eth0` | rejected: matching ignores zones, so it would mean `fe80::1` from any interface |
| `10.20.30.7` and `10.20.30.7/32` in one file | rejected: same coverage, matching would be arbitrary |

### Matching

- A device inside a range with a credential set is polled from its first flow, without being named anywhere.
- Longest prefix wins, so a host entry overrides the segment it sits in.
- Registration follows flows. Declaring `10.0.0.0/16` walks the devices that send flows, not 65,536 addresses. Size `pool-width` for the active devices and their cadences, and read `snmp.poller.exporters` against `max-exporters`.
- A range that declares nothing still matches and shadows wider ranges. The loader warns about it and suggests `enabled: false` if an exclusion was meant.

:::warning
A `v1` or `v2c` credential set on a range wider than one address fails the load, whatever `enabled` says.
The community would otherwise go to any in-range address that emits a flow.
List the devices as single addresses, or move the segment to `v3`.
:::

### Load errors

A failed load names every bad entry it reached, one problem per entry, up to 20 entries.
An exporter's interface pins count up to five problems per entry.
The coverage collision check runs only on a file that is otherwise clean.

```text
Inventory file /etc/riptide/inventory.yaml carries problems in 5 entries:
  - The agent range '10.0.1.5/24' has host bits set for its /24 prefix; write '10.0.1.0/24' (the covered block) or '10.0.1.5' (the single host).
  - Agent range '10.20.30.9' references credential set 'nope' which is not defined.
  - The agent range '10.20.30.10' has an unknown key 'pooling'; known keys are [credentials, enabled, polling, port].
  - The agent range 'fe80::1%eth0' has a zone id (%eth0); matching ignores zones, so the entry would silently mean 'fe80::1' from any interface; write 'fe80::1'.
  - The agent range '010.1.1.1' has leading zeros; write '10.1.1.1' (some tools read leading zeros as octal — confirm that is the address you meant).
```

At startup this fails the process. During a hot reload it keeps the last good inventory, see below.

## Hot reload

With `riptide.config.reload-interval` set, see [config hot-reload](../operations/hot-reload.md), the inventory file is re-read on content change.

Expected output at startup:

```text
o.riptide.config.InventoryFileReloader   : Inventory hot-reload enabled: watching /etc/riptide/inventory.yaml every PT30S
```

| Change | Effect | Logged |
| --- | --- | --- |
| A range added, changed or carved out | Published; a carved-out agent stops being polled without a restart | `Inventory reloaded from /etc/riptide/inventory.yaml: 5 agent ranges, 0 enrichment entries` |
| A file that fails to load | Last good inventory keeps serving; `inventory.reload.stale` goes to 1, `inventory.reload.failures` counts | `Inventory reload failed, keeping the last good inventory: Inventory file /etc/riptide/inventory.yaml carries problems in 1 entry:` followed by the entries |
| A populated `agents` or `exporters` tree is absent | Refused, because that is what a partially written file looks like. Write atomically: temp file, then `mv` | `Inventory file /etc/riptide/inventory.yaml would drop a whole tree (5 -> 0 agent range(s), 0 -> 0 enrichment entry/entries): keeping the running inventory (a partially written file reads this way; write atomically via mv). To deliberately empty a tree, write it as an explicit empty mapping (agents: {} / exporters: {}); to stop polling while keeping entries, set enabled: false on a covering range` |
| `agents: {}` or `riptide: {}` written explicitly | Published as authored, because truncation cannot produce that spelling | `Inventory reloaded from /etc/riptide/inventory.yaml: 0 agent ranges, 0 enrichment entries` |
| An empty or whitespace-only file | Cycle skipped, running inventory kept | `Inventory file /etc/riptide/inventory.yaml is empty or whitespace-only: skipping reload cycle (truncate-write race or intentional; keeping the running inventory)` |
| Credential sets or polling profiles edited in the main configuration | The inventory is rebuilt against the new profiles and registered agents re-resolve, so a changed reference reaches running walks without a restart. Until the rebuild publishes, `config.reload.stale` stays at 1 | `Inventory refresh: N registration(s) re-resolved, M stopped, of K polled` |

A tree that shrinks without vanishing publishes normally.
With discovery on, `exporters: {}` does not apply: the file may not carry an `exporters` tree at all while discovery owns it.

## Error catalog

Every message below fails startup, or fails a reload while the last good inventory keeps serving.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `Credential set 'X' has no version: one of v1, v2c or v3 is required.` | A set named only by its other fields | Add `version` |
| `Credential set 'X' (v2c) has no community.` | Community missing on `v1` or `v2c` | Add `community` |
| `Credential set 'X' (v2c) carries v3 fields; remove them or set version v3.` | USM fields on a community set | Remove them, or change the version |
| `Credential set 'X' (v3) has no security-name.` | | Add `security-name` |
| `Credential set 'X' (v3) carries a community; remove it or set version v1/v2c.` | | Remove `community` |
| `Credential set 'X' (v3) pairs auth-protocol and auth-passphrase incompletely: set both or neither.` | One of the pair missing | Set both, or neither |
| `Credential set 'X' (v3) pairs priv-protocol and priv-passphrase incompletely: set both or neither.` | | Set both, or neither |
| `Credential set 'X' (v3) sets priv without auth: USM has no priv-only security level.` | | Add the auth pair |
| `Polling profile 'X' has a non-positive timeout (0 ms).`, `... has negative retries (-1).`, `... has a non-positive refresh-interval (PT0S).`, `... has a non-positive snapshot-expiry (...)` | Zero or negative value | Use a positive value |
| `Polling profile 'X' has a refresh-interval of PT48H, over the PT24H maximum.` | Cadence over one day | Shorten it |
| `riptide.snmp.polling.X: timeout N ms x M attempts exceeds the walk budget of K ms (80% of refresh-interval); lower the timeout or lengthen refresh-interval.` | `collect` is set and `timeout x (retries + 1)` does not fit in 80 percent of `refresh-interval` | Lower `timeout` or `retries`, or lengthen `refresh-interval` |
| `Polling profile 'Default': the default profile must be spelled exactly 'default', because agent ranges without a 'polling' key resolve that name.` | Mis-cased `default` | Rename it |
| `Retired per-agent poll key found ('riptide.snmp.poll.refresh-interval-ms'): refresh and expiry moved into named polling profiles ...` | Pre-0.9 cadence keys | Move the values to a profile |
| `Inventory file /path is not readable: /path` | `riptide.inventory.file` names a missing or unreadable file | Fix the path or its permissions |
| `Inventory file /path is not valid YAML: ...` | Parse error | Fix the YAML |
| `Inventory file /path carries problems in N entries:` | One or more entries listed below it are wrong | Fix each listed entry |
| `Agent range 'X' references credential set 'Y' which is not defined.` | Dangling reference | Define the set, or fix the name |
| `Agent range 'X' references polling profile 'Y' which is not defined.` | Dangling reference | Define the profile, or fix the name |
| `The agent range 'X' has an unknown key 'Y'; known keys are [credentials, enabled, polling, port].` | Typo | Fix the key |
| `Agent range 'X' has a port N outside 1..65535.`, `... has a port 'Y' that is not a whole number.` | | Fix the port |
| `Agent range 'X' has a non-boolean enabled value 'Y': write it unquoted as true or false.` | Quoted or misspelled boolean | Write `true` or `false` |
| `Agent range 'X' uses credential set 'Y', which speaks v2c, but is wider than a single address: ...` | Cleartext community on a range | Single addresses, or `v3` |
| `The agent range 'X' has host bits set ...`, `... has leading zeros ...`, `... is inet_aton shorthand ...`, `... uses a netmask ...`, `... is a wildcard spelling ...`, `... has a zone id ...` | See [address forms](#address-forms) | Write the form the message names |
| `Ambiguous matcher entries: 'X' and 'Y' resolve to the same canonical prefix with the same observation-domain pinning ...` | Two spellings of one coverage | Merge them |
| `Unknown key 'X' under 'riptide.snmp'; known keys are [agents].` | Misplaced tree | Move it |

## Open questions

- Propagation of edited credential sets and polling profiles into running walks is documented from the reloader source. It was not exercised live on this page; the inventory file paths were.
