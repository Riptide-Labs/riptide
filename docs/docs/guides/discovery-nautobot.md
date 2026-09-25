---
title: Discover exporters from Nautobot
description: Read the exporter list from Nautobot's device API with mapped-json, why depth=1 and primary_ip4.host are required, and how to verify it.
---

# Discover exporters from Nautobot

Nautobot is a NetBox fork, but **`netbox-api` cannot read it**: that source appends `ordering=id`, which Nautobot rejects with `HTTP 400 {"ordering":["Unknown filter field"]}`.
No `filter` value rescues it; the append is only suppressed when the filter already contains an `ordering=` term, and Nautobot rejects that too.
`mapped-json` reads Nautobot as it is, with nothing installed on the Nautobot side.

## Prerequisites {/* #nautobot */}

| Requirement | Detail |
| --- | --- |
| A Nautobot API token | Read access to devices. Stored where a [secret reference](../reference/secret-references.md) resolves it. Nautobot expects the `Token` scheme, the default. |
| Every device has a primary IPv4 | A device whose `primary_ip4` is null is dropped by the source and not counted. |
| Device names unique across the devices the filter returns | Two devices with one name and different addresses refuse the whole poll. |
| An internal CA, if Nautobot uses one | In **`riptide.http.ca-bundle`**, see [Outbound TLS](../reference/outbound-tls.md). |
| No `exporters` tree in the inventory file | Discovery owns that tree. |

## Steps

1. Write the discovery block into `/etc/riptide/config.yaml`.

   ```yaml
   riptide:
     discovery:
       url: https://nautobot.example.com/api/dcim/devices/
       token: vault://secret/nautobot#token
       type: mapped-json
       filter: depth=1
       mapping:
         items: results
         name: name
         address: primary_ip4.host
         next: next
     inventory:
       file: /etc/riptide/inventory.yaml
   ```

   Two things in that block are load-bearing.

   - **`filter: depth=1`** is required. Without it Nautobot serves `primary_ip4` as a reference carrying only `id`, `object_type` and `url`, so there is no address to map and every device is skipped.
   - **`primary_ip4.host`** rather than `primary_ip4.address`. Nautobot serves both: `address` is `10.0.0.1/24` and cannot be used as an exporter address, while `host` is the bare `10.0.0.1`. Mapping `address` fails the poll and tells you so, naming the values it found.

   Nautobot's own filters go in the same `filter` value, joined with `&`, for example `filter: depth=1&status=active`.
   Paging needs no ordering term: Nautobot orders devices by name by default, so a walk in pages is stable without one.
   Measured across a three-page walk of five devices: no duplicates, none missed, identical on every read.
   Nautobot's ordering term is `sort`, and `sort=id` is not insertion order the way NetBox's `ordering=id` is, because Nautobot's primary keys are UUIDs.
   The default is almost certainly what you want.

2. Reduce `/etc/riptide/inventory.yaml` to agent ranges only.

   ```yaml
   riptide:
     snmp:
       agents:
         "10.20.0.0/16":
           credentials: corp-v3
           polling: default
   ```

3. Restart the collector and read the inventory line.

   ```bash
   journalctl -u riptide -n 200 | grep -E 'Inventory|Boot could not reach'
   ```

   Expected output:

   ```text
   2026-09-23T14:40:53.632+02:00  INFO 23517 --- [           main] org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml + https://nautobot.example.com/api/dcim/devices/: 1 agent ranges, 2 enrichment entries
   2026-09-23T14:40:53.671+02:00  INFO 23517 --- [           main] o.riptide.config.InventoryFileReloader   : Inventory hot-reload enabled: watching /etc/riptide/inventory.yaml + https://nautobot.example.com/api/dcim/devices/ every PT1M
   ```

4. Verify the gauges on the management port.

   ```bash
   curl -s http://localhost:8080/metrics | grep -E '^(discovery_|inventory_reload_)'
   ```

   Expected output:

   ```text
   discovery_skipped 0.0
   discovery_targets 2.0
   inventory_reload_dead 0.0
   inventory_reload_stale 0.0
   inventory_reload_failures 0.0
   inventory_reload_successes 0.0
   ```

   The page behind this run held three devices, one with `primary_ip4: null`.
   That device is missing from `discovery_targets` and absent from `discovery_skipped` too: a path that matches nothing drops the device without counting it.
   Only an address that is present but unusable, such as one carrying a prefix length, is counted.

## Related

- [Discovery reference](../reference/discovery.md): the mapping path rules and every message.
- [Discover exporters from any JSON endpoint](discovery-mapped-json.md): the same source against an endpoint of your own.
- [How discovery composes the inventory](../architecture/discovery.md): why `netbox-api` appends `ordering=id` and `mapped-json` does not.

## Open questions

- Verified against Nautobot 3.2.5 in an earlier session. The output above was captured on 2026-09-23 against a local stand-in serving a three-device Nautobot-shaped page with `depth=1` echoed in the request, not against a Nautobot server; the inventory path and the URL in the quoted lines were substituted for the stand-in's.
- `journalctl` was not run; the lines were read from the collector's stdout.
