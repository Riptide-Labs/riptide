---
title: Discover exporters from Prometheus service discovery
description: Read the exporter list from any endpoint that serves a Prometheus HTTP service discovery document, including the NetBox prometheus-sd plugin, and verify it from the metrics endpoint.
---

# Discover exporters from Prometheus service discovery

`prometheus-sd` is the default source.
It reads a bare JSON array of `{targets, labels}` objects, the contract many systems already publish for Prometheus to scrape.
Nothing needs installing unless the producer is NetBox, where the [`netbox-plugin-prometheus-sd`](https://github.com/FlxPeters/netbox-plugin-prometheus-sd) plugin serves the document.

## Prerequisites

| Requirement | Detail |
| --- | --- |
| An endpoint serving a Prometheus HTTP SD document | A bare array, no envelope. Each target is `host` or `host:port`; a NetBox device's target is its name. |
| An address per device | From the first present label in `riptide.discovery.address-labels` (default `__meta_netbox_primary_ip4`, then `__meta_netbox_primary_ip6`), else the target's host when it parses as an address. |
| A token, if the endpoint needs one | Stored where a [secret reference](../reference/secret-references.md) resolves it. |
| An internal CA, if the endpoint uses one | In **`riptide.http.ca-bundle`**, see [Outbound TLS](../reference/outbound-tls.md). |
| No `exporters` tree in the inventory file | Discovery owns that tree. |

## Steps

1. Point `riptide.discovery.url` at the document in `/etc/riptide/config.yaml`.
   The example is the NetBox plugin endpoint; a producer that is not NetBox needs `address-labels` set to whichever label carries its address, or no label at all when the target itself is the address.

   ```yaml
   riptide:
     discovery:
       url: https://netbox.example.com/api/plugins/prometheus-sd/devices/
       token: vault://secret/netbox#token
       interval: 60s
     inventory:
       file: /etc/riptide/inventory.yaml
   ```

   `type` may stay unset; it defaults to `prometheus-sd`.
   `filter` is ignored by this source: the producer filters, so bound the NetBox plugin with its own query, for example `?status=active&role=leaf&role=spine` on the URL.

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
   2026-09-23T14:40:36.702+02:00  INFO 23264 --- [           main] org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml + https://netbox.example.com/api/plugins/prometheus-sd/devices/: 1 agent ranges, 2 enrichment entries
   2026-09-23T14:40:36.761+02:00  INFO 23264 --- [           main] o.riptide.config.InventoryFileReloader   : Inventory hot-reload enabled: watching /etc/riptide/inventory.yaml + https://netbox.example.com/api/plugins/prometheus-sd/devices/ every PT1M
   ```

4. Verify the gauges on the management port.

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

   The document behind this run held three devices, one of them with no `__meta_netbox_primary_ip4` label and a name for a target, so it was skipped and counted.

## Related

- [Discovery reference](../reference/discovery.md): every key and every message.
- [How discovery composes the inventory](../architecture/discovery.md): how a target becomes an exporter, and what the plugin endpoint costs per poll.
- [Discover exporters from NetBox](discovery-netbox.md): the reader to prefer for NetBox when the plugin is not already in place.

## Open questions

- The output above was captured on 2026-09-23 against a local stand-in serving a three-entry service discovery document, not against the NetBox plugin; the inventory path and the URL in the quoted lines were substituted for the stand-in's.
- `journalctl` was not run; the lines were read from the collector's stdout.
