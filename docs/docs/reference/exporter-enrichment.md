---
sidebar_position: 3
title: Exporter enrichment
description: The exporters tree of the inventory file, how an entry is matched to a flow's exporter, how interface pins override SNMP, and every loader message.
---

# Exporter enrichment reference

What a flow's exporter is called, and what its interfaces are called.
Entries live in the `exporters` tree of the inventory file named by `riptide.inventory.file`, next to the [agent ranges](agent-configuration.md).
The map key of an entry is the name stamped into `exporterName` on every flow it matches.

With [dynamic discovery](discovery.md) on, discovery owns this tree, and an `exporters` tree in the inventory file fails startup:

```text
/etc/riptide/inventory.yaml declares an 'exporters' tree while riptide.discovery.url is set. Discovery owns the exporters tree and the inventory file owns snmp.agents, so an entry can never have two possible sources. Remove the exporters tree from the file, or unset riptide.discovery.url.
```

## Entry keys

```yaml
# /etc/riptide/inventory.yaml
riptide:
  exporters:
    core-router:
      address: 10.20.30.7
      observation-domain: 42
      interfaces:
        "3":
          name: ge-0/0/3
          alias: Peering with AS64500
          high-speed: 10000
    campus:
      address: 10.20.0.0/16
```

Expected output at startup:

```text
org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml: 0 agent ranges, 2 enrichment entries
```

| Key | Type | Default | Description |
| --- | --- | --- | --- |
| **`address`** | host address or CIDR prefix, IPv4 or IPv6 | required | What the entry covers. A prefix names every device inside it. The accepted and rejected spellings are the same as for [agent ranges](agent-configuration.md#address-forms). |
| **`observation-domain`** | int, 0 to 4294967295 | unset, matches any | Pins the entry to one observation domain of the device. |
| **`interfaces`** | map of ifIndex to pin | empty | Static per-interface facts, see [interface pins](#interface-pins). |
| **`interfaces.<ifIndex>.name`** | text | unset | Short interface name, such as `ge-0/0/3`. |
| **`interfaces.<ifIndex>.alias`** | text | unset | Operator label, `ifAlias`. |
| **`interfaces.<ifIndex>.high-speed`** | int, 1 to 4294967295 | unset | Speed in Mbit/s, like `ifHighSpeed`. |

Unknown keys fail the load.
An entry without an `address` fails the load.

## Matching

Order in the file does not matter.

| Step | Rule |
| --- | --- |
| 1 | An entry pinned to the flow's observation domain beats every unpinned entry, including a more specific one. |
| 2 | Among the remaining candidates the longest prefix wins. A host address is the most specific. |
| 3 | Two entries with the same coverage and the same pin fail the load. |

An entry pinned to a different observation domain is not a candidate at all.
In the example above a flow from `10.20.30.7` with source ID 7 is named `campus`, not `core-router`, because only entries pinned to 7 and unpinned entries are consulted.

What is matched depends on the protocol:

| Protocol | Address | Observation domain |
| --- | --- | --- |
| NetFlow v9 | UDP source | header source ID |
| IPFIX | UDP or TCP source | header observation domain ID |
| NetFlow v5 | UDP source | engine type times 256, plus engine ID |
| sFlow | `agent_address` from the datagram, which may differ from the UDP source | `sub_agent_id` |

`observation-domain: 0` therefore pins both NetFlow v5 exporters with engine type and engine ID 0 and sFlow agents with the default `sub_agent_id` of 0, and that pin beats any wildcard entry.
In a subnet that mixes the two, distinguish entries by address instead of pinning 0.

A flow whose exporter matches no entry is still collected and enriched from option data.
It carries no `exporterName` and shows by address on dashboards.

The tie error, on a file that is otherwise clean:

```text
Inventory file /etc/riptide/inventory.yaml: Ambiguous matcher entries: 'a' and 'b' resolve to the same canonical prefix with the same observation-domain pinning — matching between them would be arbitrary. Merge them or distinguish them by prefix or observation-domain.
```

## Interface pins

A pinned field wins over the same field from exporter option data and from an SNMP walk.
Fields left unset fall through, so pinning only `alias` keeps the `name` and `high-speed` that option data or the SNMP walk supply; see the [enrichment ladder](../architecture/enrichment.md#the-enrichment-ladder).
Pins need no agent range, so they also label devices that are never polled.

Pins belong to the entry, not to a device.
On a prefix entry they apply to every device the prefix covers: pinning ifIndex 3 on `10.20.0.0/16` labels interface 3 on every device in that segment.
Pin interfaces on host entries unless the segment shares one interface layout.

Write the ifIndex quoted, `"3"`.
An unquoted key also loads, but YAML 1.1 reads `010` as octal, so the entry silently pins ifIndex 8.
One ifIndex written both quoted and unquoted fails the load.
`name` and `alias` must be text: an unquoted `on` arrives as a boolean and an unquoted date as a timestamp, and the load fails asking for quotes.
A pin with no field at all loads with a warning:

```text
org.riptide.inventory.InventoryLoader    : Exporter 'octal' interface 6 pins nothing: give it a name, alias or high-speed, or remove the entry
```

## Naming and polling are independent

An entry never causes SNMP traffic, and an agent range never names a flow.
A device can be named and unpolled, polled and unnamed, or both.
One credentialed range polls a whole segment while naming stays a per-device decision.

## Error catalog

A failed load names every bad entry it reached, up to 20 entries and five problem lines per entry.
Past those caps the report ends an entry with `    and N more in this entry, listed no further` and the list with `  problems in N entries are listed no further`.
At startup it fails the process; during a [hot reload](agent-configuration.md#hot-reload) the last good inventory keeps serving.

```text
Inventory file /etc/riptide/inventory.yaml carries problems in 5 entries:
  - Exporter 'no-address' has no address — every enrichment entry needs one.
  - Exporter 'both-spellings' pins interface 3 twice, once quoted and once not: keep one spelling.
  - Exporter 'bool-name' interface 4 has a name that is not text ('true'); quote it.
  - Exporter 'big-speed' interface 5 has a high-speed 5000000000 Mbit/s outside 1..4294967295.
  - Exporter 'big-domain' observation-domain 4294967296 is outside the unsigned 32-bit range.
```

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `Exporter 'X' has no address — every enrichment entry needs one.` | Missing `address` | Add it |
| `The exporter 'X' must be a mapping, found Y.` | Entry written as a scalar or list | Write the keys under it |
| `The exporter 'X' has a non-string key 'Y' — quote it.` | Unquoted numeric key inside the entry | Quote it |
| `The exporter 'X' has an unknown key 'Y'; known keys are [address, interfaces, observation-domain].` | Typo | Fix the key |
| `The exporter 'X' address 'Y' ...` | Rejected address spelling | Write the form the message names, see [address forms](agent-configuration.md#address-forms) |
| `Exporter 'X' observation-domain 'Y' is not a whole number.`, `... observation-domain N is outside the unsigned 32-bit range.` | | Use 0 to 4294967295 |
| `Exporter 'X' interfaces must be a mapping of ifIndex to pins, found Y.` | A list or scalar under `interfaces` | Write a mapping keyed by ifIndex |
| `Exporter 'X' pins interface N twice, once quoted and once not: keep one spelling.` | `3:` and `"3":` in one entry | Keep one |
| `Exporter 'X' has an interface key 'Y': write the ifIndex as plain decimal digits, with no sign, padding or leading zeros.` | Quoted key such as `"03"` or `"+3"` | Write `"3"` |
| `Exporter 'X' has an interface key 'Y' outside the ifIndex range.`, `... that is not a whole number.`, `... pins interface N, which is not a usable ifIndex: it must be positive.` | Zero, negative or non-numeric key | Use a positive integer |
| `Exporter 'X' interface N must be a mapping, found Y.` | Scalar pin | Write `name`, `alias` or `high-speed` under it |
| `Exporter 'X' interface N has an unknown key 'Y'; known keys are [alias, high-speed, name].` | Typo | Fix the key |
| `Exporter 'X' interface N has a non-string key 'Y'; quote it.` | Unquoted numeric key inside a pin | Quote it |
| `Exporter 'X' interface N has a name that is not text ('true'); quote it.` | Unquoted `on`, `yes`, a number or a date | Quote the value |
| `Exporter 'X' interface N has a blank name: remove the key to fall back to SNMP.` | Empty string | Remove the key |
| `Exporter 'X' interface N has a high-speed 'Y' that is not a whole number.`, `... has a high-speed N Mbit/s outside 1..4294967295.` | | Use 1 to 4294967295 |
| `Ambiguous matcher entries: 'X' and 'Y' resolve to the same canonical prefix with the same observation-domain pinning ...` | Two spellings of one coverage with the same pin | Merge them, or pin one |
| `/path declares an 'exporters' tree while riptide.discovery.url is set. ...` | Discovery and the file both supply exporters | Remove the tree from the file |
