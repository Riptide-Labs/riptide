---
title: Discover exporters from NetBox
sidebar_position: 15
description: Read the exporter list from NetBox's device API with nothing installed on NetBox, narrowed by a NetBox filter, and verify it from the metrics endpoint.
---

# Discover exporters from NetBox

`netbox-api` reads `/api/dcim/devices/` directly, follows pagination to completion, and passes your filter in NetBox's own query terms.
If the `netbox-plugin-prometheus-sd` plugin is already installed and you would rather not touch it, follow [Discover exporters from Prometheus service discovery](discovery-prometheus-sd.md) instead.

## Prerequisites

| Requirement | Detail |
| --- | --- |
| A NetBox API token | Read access to devices. Stored where a [secret reference](../reference/secret-references.md) can resolve it. |
| Every device has a primary IPv4 or IPv6 | A device with neither is skipped and counted on `discovery.skipped`. |
| Device names unique across the devices the filter returns | Two devices with one name and different addresses refuse the whole poll. NetBox enforces uniqueness per site only. |
| An internal CA, if NetBox uses one | In **`riptide.http.ca-bundle`**, see [Outbound TLS](../reference/outbound-tls.md). |
| No `exporters` tree in the inventory file | Discovery owns that tree; a file that declares one fails startup and every later poll. |

## Steps

1. Store the token where riptide can resolve it, for example in Vault at `secret/netbox` under the key `token`.

2. Write the discovery block into `/etc/riptide/config.yaml`.
   Develop the filter against NetBox directly and paste in what works; it is passed through unchanged.

   ```yaml
   riptide:
     discovery:
       type: netbox-api
       url: https://netbox.example.com/api/dcim/devices/
       token: vault://secret/netbox#token
       filter: status=active&role=leaf&role=spine
       interval: 60s
     inventory:
       file: /etc/riptide/inventory.yaml
   ```

   Leave `address-labels` unset: `netbox-api` emits exactly the default two names, and a customised list fails startup.

3. Reduce `/etc/riptide/inventory.yaml` to agent ranges only.

   ```yaml
   riptide:
     snmp:
       agents:
         "10.20.0.0/16":
           credentials: corp-v3
           polling: default
   ```

4. Restart the collector and read the inventory line.

   ```bash
   journalctl -u riptide -n 200 | grep -E 'Inventory|Boot could not reach'
   ```

   Expected output:

   ```text
   2026-09-23T14:40:41.098+02:00  INFO 23358 --- [           main] org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml + https://netbox.example.com/api/dcim/devices/: 1 agent ranges, 2 enrichment entries
   2026-09-23T14:40:41.140+02:00  INFO 23358 --- [           main] o.riptide.config.InventoryFileReloader   : Inventory hot-reload enabled: watching /etc/riptide/inventory.yaml + https://netbox.example.com/api/dcim/devices/ every PT1M
   ```

   A `WARN Boot could not reach ...` line instead means NetBox was down at boot; the collector runs without exporter names until the next successful poll.

5. Verify the gauges on the management port.

   ```bash
   curl -s http://localhost:8080/metrics | grep -E '^(discovery_|inventory_reload_)'
   ```

   Expected output:

   ```text
   discovery_skipped 1.0
   discovery_targets 2.0
   inventory_reload_dead 0.0
   inventory_reload_stale 0.0
   inventory_reload_failures 0.0
   inventory_reload_successes 0.0
   ```

   `discovery_targets` is the number of exporter entries serving.
   `discovery_skipped` counts devices the filter returned without a usable primary IP; here one of three devices had none.
   `inventory_reload_successes` stays at `0.0` until the first poll after boot finds a change.

## Related

- [Discovery reference](../reference/discovery.md): every key, the limits, and every message.
- [How discovery composes the inventory](../architecture/discovery.md): what is refused and why, and what a boot with NetBox down does.
- [Discover exporters from Nautobot](discovery-nautobot.md): `netbox-api` cannot read Nautobot.

## Open questions

- The output above was captured on 2026-09-23 against a local stand-in serving a three-device NetBox page, not against a NetBox server; the inventory path and the URL in the quoted lines were substituted for the stand-in's. An earlier session verified this source against a real NetBox; this page's output was not captured there.
- `journalctl` was not run; the lines were read from the collector's stdout.
