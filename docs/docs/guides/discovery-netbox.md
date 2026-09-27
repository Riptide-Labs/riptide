---
title: Discover exporters from NetBox
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

## Discover devices and virtual machines

NetBox serves devices and virtual machines from two endpoints.
List both under **`riptide.discovery.urls`** instead of `url`.
The token needs view permission on both object types.
A token that cannot view virtual machines gets an empty list, not an error, and riptide refuses the poll naming the virtual machine endpoint.

1. Replace `url` with `urls` in `/etc/riptide/config.yaml`.
   Every other key applies to both endpoints, the filter included.
   Use filter terms that both object types accept, such as `tag`, `status` or `site`, and check the filter against each endpoint in NetBox directly.
   A device-only term such as `manufacturer` means nothing to the virtual machine endpoint.

   ```yaml
   riptide:
     discovery:
       type: netbox-api
       urls:
         - https://netbox.example.com/api/dcim/devices/
         - https://netbox.example.com/api/virtualization/virtual-machines/
       token: vault://secret/netbox#token
       filter: tag=flow-exporter
       interval: 60s
     inventory:
       file: /etc/riptide/inventory.yaml
   ```

   Setting both `url` and `urls` fails startup naming both keys.

2. Restart the collector and read the inventory line.

   ```bash
   journalctl -u riptide -n 200 | grep -E 'Inventory loaded|Boot could not reach'
   ```

   Expected output:

   ```text
   2026-09-25T02:20:20.109+02:00  INFO 56752 --- [           main] org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml + https://netbox.example.com/api/dcim/devices/, https://netbox.example.com/api/virtualization/virtual-machines/: 2 agent ranges, 7 enrichment entries
   ```

   The enrichment entries are the tagged devices plus the tagged virtual machines with a primary IP.
   A virtual machine with no primary IP is counted on `discovery_skipped`.

Riptide reads the endpoints in order on every poll and publishes only when both answer.
One endpoint that is down, answers 404 or answers empty keeps the last good inventory serving for both, and the log names that endpoint.
A device and a virtual machine with the same name and different addresses are refused as a collision, each address followed by its endpoint.

## Poll devices that send no flows

A device with no flows of its own, such as an access switch that only aggregates other traffic, never gets an exporter entry from a flow.
Tag it in NetBox and name the tag in `riptide.discovery.poll-always-tag`, and discovery composes it with `poll: always` instead, so it is polled for SNMP metrics as soon as the inventory loads.
This is a NetBox tag rather than a `netbox-api`-only feature: the renderer keys on the `__meta_netbox_tags` label, and any producer that emits it, including a `prometheus-sd` document that copies it in, is honoured the same way.

1. Tag the device in NetBox, for example `snmp-poll`.

2. Name the tag in `/etc/riptide/config.yaml`.

   ```yaml
   riptide:
     discovery:
       type: netbox-api
       url: https://netbox.example.com/api/dcim/devices/
       token: vault://secret/netbox#token
       filter: tag=flow-exporter
       poll-always-tag: snmp-poll
       interval: 60s
     inventory:
       file: /etc/riptide/inventory.yaml
   ```

   A device carries the tag or not; nothing here narrows which devices `filter` returns.
   `poll-always-tag` only decides which of the returned devices are marked, and needs a primary IP like any other device.

3. Restart the collector and read the inventory line, as in the earlier steps.
   The composed exporters tree is not written anywhere an operator can read directly; `poll: always` on the tagged device is what SNMP polling reads to poll it without waiting for a flow.

4. Verify the device is actually being polled, on the management port.

   ```bash
   curl -s http://localhost:8080/metrics | grep snmp_poller_inventoryRegistered
   ```

   A count at or above 1 means at least one `poll: always` entry, tagged device included, is registered and walked.
   The gauge does not name which device; if you need that, and `riptide.metrics.remote-write.url` is set, query the store for `riptide_interface_info{exporter="<name>"}` instead, which carries the tagged device's own name.

If you run more than one riptide collector against the same NetBox, each collector's `riptide.discovery.filter` names its own shard, for example `tag=riptide-shard-a` on one collector and `tag=riptide-shard-b` on another.
Moving a device between shards is a retag in NetBox, not a config change on either collector.
A dead collector's shard stays unpolled until it comes back or an operator retags its devices onto a live one; nothing here reassigns a shard automatically.

## Related

- [Discovery reference](../reference/discovery.md): every key, the limits, and every message.
- [How discovery composes the inventory](../architecture/discovery.md): what is refused and why, and what a boot with NetBox down does.
- [Discover exporters from Nautobot](discovery-nautobot.md): `netbox-api` cannot read Nautobot.

## Open questions

- The output above was captured on 2026-09-23 against a local stand-in serving a three-device NetBox page, not against a NetBox server; the inventory path and the URL in the quoted lines were substituted for the stand-in's. An earlier session verified this source against a real NetBox; this page's output was not captured there.
- `journalctl` was not run; the lines were read from the collector's stdout.
- The devices-and-virtual-machines output was captured on 2026-09-25 against a real NetBox `v4.7-5.1.1` lab on `http://localhost:18000`, with 7 tagged devices and 3 tagged virtual machines, one without a primary IP. The URL and the inventory path in the quoted line were substituted.
- Step 4 of "Poll devices that send no flows" names `snmp_poller_inventoryRegistered` and `riptide_interface_info` from source, not from a captured run against a tagged device; no expected output is quoted for it.
