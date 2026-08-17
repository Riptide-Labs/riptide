---
sidebar_position: 3
title: Exporter enrichment
---

# Exporter enrichment entries

What a flow's exporter is called, and what its interfaces are called.
Enrichment entries live in the inventory file named by `riptide.inventory.file`, alongside the [agent ranges](agent-configuration.md); the entry's map key is the exporter name that lands on its flows.

```yaml
riptide:
  exporters:
    core-router:
      address: 10.20.30.7
      observation-domain: 42     # optional pin; omit = matches any
      interfaces:
        "3":
          name: ge-0/0/3
          alias: Peering with AS64500
          high-speed: 10000      # Mbit/s, like ifHighSpeed
    campus:                      # a prefix entry names everything it covers
      address: 10.20.0.0/16
```

## Matching

Order-free, like a routing table:

1. An entry **pinned** to the flow's observation domain beats a wildcard entry.
2. Among the rest, the **longest prefix wins**; a bare host address is most specific.
3. A true tie (same coverage, same pinning) fails the load naming both entries.

An entry may carry a prefix, so a whole segment can share one label while host entries inside it override.
For NetFlow/IPFIX the matched address is the UDP source and the pin is the observation domain (source ID); for **sFlow** both come from the datagram payload: the `agent_address`, which may differ from the UDP source, and the `sub_agent_id`.

:::warning[The pin key is shared across protocols]
`observation-domain: 0` pins *both* NetFlow v5 exporters with engine type/ID 0 *and* sFlow agents with the near-universal default `sub_agent_id = 0`, and a matching pin beats every wildcard entry, even a more-specific one.
If a subnet mixes NetFlow v5 and sFlow devices, avoid pinning `0`; distinguish the entries by address instead.
:::

An exporter no entry matches still has its flows collected and option-data enriched; it shows by address on dashboards.
That is the intended "someone should decide on a name" signal, not a defect.

## Interface pins

Fields set under `interfaces` **override** live SNMP values per field; SNMP fills the rest (see the [enrichment ladder](../enrichment.md#the-enrichment-ladder)).
Pins work without any agent range too, for devices you never poll.

Interface keys are written as quoted decimal (`"3"`).
Unquoted works for plain numbers, but YAML 1.1 reads unquoted `010` as octal 8, so the quoted canonical form is the one that never surprises; declaring one ifIndex in both spellings is an error.
`name` and `alias` must be text: quote a value like `on` or a bare date, or YAML will hand the loader a boolean or a timestamp and the load fails telling you to quote it.

:::warning[Pins are entry-scoped]
Interface pins belong to the *entry*, not to a device.
On a prefix entry they apply to **every device the prefix covers**: pinning ifIndex 3 on `10.20.0.0/16` labels interface 3 on every device in that segment.
Pin interfaces on host entries unless the segment genuinely shares an interface layout.
:::

## Interaction with polling

Naming and polling are independent: an enrichment entry never causes SNMP traffic, and an [agent range](agent-configuration.md) never names a flow.
A device can be named-but-unpolled (entry only), polled-but-unnamed (range only, shows by address), or both.
This is what makes zero-touch onboarding safe: one credentialed range polls a whole segment, while naming stays a per-device decision you take when you care.
