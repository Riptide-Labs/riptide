---
title: Discover exporters from any JSON endpoint
sidebar_position: 18
description: Read the exporter list from a home-grown asset database, a CMDB or any JSON endpoint by naming where the devices, the name, the address and the next page are.
---

# Discover exporters from any JSON endpoint

`mapped-json` points at an endpoint you already serve and says which fields hold the exporter name and address, instead of running a service to translate your inventory into a format riptide already knows.
For NetBox use [`netbox-api`](discovery-netbox.md); for Nautobot use [this source with the Nautobot mapping](discovery-nautobot.md).

## Prerequisites

| Requirement | Detail |
| --- | --- |
| A JSON endpoint answering `200` to an unconditional `GET` | Either a bare array of devices, or an envelope with the array at a path you can name. |
| A bare address per device | `10.0.0.1`, not `10.0.0.1/24`. A path is field names only; there is no transform to strip a prefix length. |
| A token, if the endpoint needs one | Stored where a [secret reference](../reference/secret-references.md) resolves it; the scheme defaults to `Token`. |
| An internal CA, if the endpoint uses one | In **`riptide.http.ca-bundle`**, see [Outbound TLS](../reference/outbound-tls.md). |
| No `exporters` tree in the inventory file | Discovery owns that tree. |

## Steps

1. Map the endpoint in `/etc/riptide/config.yaml`.
   Three paths are required, `items` turns on an envelope, and `next` turns on paging.

   ```yaml
   riptide:
     discovery:
       url: https://assets.internal/api/devices
       type: mapped-json
       mapping:
         items: payload.inventory.nodes   # unset if the response is itself the array
         name: identity.fqdn
         address: net.mgmt.v4
         next: cursor.more
     inventory:
       file: /etc/riptide/inventory.yaml
   ```

   That configuration reads this response, and then follows `cursor.more` to the next page:

   ```json
   {
     "payload": {"inventory": {"nodes": [
       {"identity": {"fqdn": "edge-01.dc1"}, "net": {"mgmt": {"v4": "10.20.1.1"}}},
       {"identity": {"fqdn": "edge-02.dc1"}, "net": {"mgmt": {"v4": "10.20.1.2"}}}
     ]}},
     "cursor": {"more": "/api/devices?page=2"}
   }
   ```

   An endpoint that answers with a bare array needs no `items` path, because a path is field names and the root has none:

   ```yaml
   riptide:
     discovery:
       url: https://assets.internal/api/devices
       type: mapped-json
       mapping:
         name: hostname
         address: mgmt_ip
   ```

   A `next` link may be absolute or relative; a relative one is resolved against the page it came from.
   It must stay on the same origin (scheme, host and port) as the endpoint it was read from, because the credential is sent with every page.
   If the endpoint pages by offset, put its own ordering term in `riptide.discovery.filter`; this source appends none of its own.
   A missing required path fails startup naming the key, rather than turning up as an empty result at the first poll.

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
   2026-09-23T14:40:58.066+02:00  INFO 23611 --- [           main] org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml + https://assets.internal/api/devices: 1 agent ranges, 2 enrichment entries
   2026-09-23T14:40:58.133+02:00  INFO 23611 --- [           main] o.riptide.config.InventoryFileReloader   : Inventory hot-reload enabled: watching /etc/riptide/inventory.yaml + https://assets.internal/api/devices every PT1M
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

   The two pages behind this run held three devices; the third carried `10.20.9.1/24` as its address, so it was skipped and counted.
   Had every address carried a prefix length, the poll would have failed instead, naming the path and the values, because then nothing was usable.

## Related

- [Discovery reference](../reference/discovery.md): the mapping path rules, the walk limits and every message.
- [How discovery composes the inventory](../architecture/discovery.md): why paths have no expression language, and what is refused.

## Open questions

- The output above was captured on 2026-09-23 against a local two-page stand-in served by `python3 -m http.server`, not against a production endpoint; the inventory path and the URL in the quoted lines were substituted for the stand-in's.
- `journalctl` was not run; the lines were read from the collector's stdout.
